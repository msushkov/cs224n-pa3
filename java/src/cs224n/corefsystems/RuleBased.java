package cs224n.corefsystems;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cs224n.coref.ClusteredMention;
import cs224n.coref.Document;
import cs224n.coref.Entity;
import cs224n.coref.Gender;
import cs224n.coref.Mention;
import cs224n.coref.Pronoun;
import cs224n.coref.Sentence;
import cs224n.util.Counter;
import cs224n.util.CounterMap;
import cs224n.util.Pair;

public class RuleBased implements CoreferenceSystem {

    HashMap<String, Gender> gender = new HashMap<String, Gender>();
    HashMap<String, Pronoun.Speaker> speaker = new HashMap<String, Pronoun.Speaker>();

    @Override
    public void train(Collection<Pair<Document, List<Entity>>> trainingData) {
        // word, gender
        CounterMap<String, Gender> genderCounter = new CounterMap<String, Gender>();

        // word, speaker
        CounterMap<String, Pronoun.Speaker> personCounter = new CounterMap<String, Pronoun.Speaker>();

        // collect gender/speaker information

        for (Pair<Document, List<Entity>> point : trainingData) {
            for (Entity e : point.getSecond()) {
                for (Pair<Mention, Mention> pair : e.orderedMentionPairs()) {
                    Mention m1 = pair.getFirst();
                    Mention m2 = pair.getSecond();
                    Sentence sent1 = m1.sentence;
                    Sentence sent2 = m2.sentence;
                    String m1Text = sent1.words.get(m1.headWordIndex);
                    String m2Text = sent2.words.get(m2.headWordIndex);

                    if (Pronoun.valueOrNull(m1Text) != null && Pronoun.valueOrNull(m2Text) == null) {
                        Pronoun curr = Pronoun.valueOrNull(m1Text);
                        if (curr != null) {
                            String word = sent2.lemmas.get(m2.headWordIndex);
                            genderCounter.incrementCount(word.toLowerCase(), curr.gender, 1);
                            personCounter.incrementCount(word.toLowerCase(), curr.speaker, 1);    
                        }
                    } else if (Pronoun.valueOrNull(m2Text) != null && Pronoun.valueOrNull(m1Text) == null) {
                        Pronoun curr = Pronoun.valueOrNull(m2Text);
                        if (curr != null) {
                            String word = sent1.lemmas.get(m1.headWordIndex);
                            genderCounter.incrementCount(word.toLowerCase(), curr.gender, 1);
                            personCounter.incrementCount(word.toLowerCase(), curr.speaker, 1);
                        }
                    } else {
                        // skip
                    }
                }
            }
        }

        // find the gender and speaker of each word
        for (String word : genderCounter.keySet()) {
            Counter<Gender> currCounter = genderCounter.getCounter(word);
            Gender bestGender = currCounter.argMax();
            gender.put(word, bestGender);
        }

        for (String word : personCounter.keySet()) {
            Counter<Pronoun.Speaker> currCounter = personCounter.getCounter(word);
            Pronoun.Speaker bestSpeaker = currCounter.argMax();
            speaker.put(word, bestSpeaker);
        }
    }

    @Override
    public List<ClusteredMention> runCoreference(Document doc) {
        // 1. do exact string match on the whole mention
        List<ClusteredMention> mentions = exactStrMatch(doc);

        // 2. do exact string match on the mention head lemma
        mentions = exactHeadMatch(mentions);

        // 3. take care of pronouns
        mentions = handlePronouns(mentions);

        return mentions;
    }

    public List<ClusteredMention> exactStrMatch(Document doc) {
        List<ClusteredMention> mentions = new ArrayList<ClusteredMention>();
        Map<String,Entity> clusters = new HashMap<String,Entity>();

        // ignore pronouns
        for (Mention m : doc.getMentions()){
            String mentionString = m.gloss();

            // if pronoun, then just add into its own cluster right away
            if (Pronoun.valueOrNull(mentionString) != null) {
                ClusteredMention newCluster = m.markSingleton();
                mentions.add(newCluster);
            } else if (clusters.containsKey(mentionString)) {
                mentions.add(m.markCoreferent(clusters.get(mentionString)));
            } else {
                ClusteredMention newCluster = m.markSingleton();
                mentions.add(newCluster);
                clusters.put(mentionString, newCluster.entity);
            }
        }
        return mentions;
    }

    // todo: add NER check
    public List<ClusteredMention> exactHeadMatch(List<ClusteredMention> mentions) {        
        Map<String,Entity> clusters = new HashMap<String,Entity>();

        // ignore pronouns
        for (int i = 0; i < mentions.size(); i++) {
            ClusteredMention c = mentions.get(i);

            Mention m = c.mention;
            String mentionHeadString = m.sentence.lemmas.get(m.headWordIndex);

            // if pronoun, then ignore
            if (Pronoun.isSomePronoun(m.gloss())) {
                // do nothing
            } else if (clusters.containsKey(mentionHeadString) && c.entity.mentions.size() == 1) {
                // if there is an exact head match and this mention was clustered by itself
                // (it was not an exact string match with anything before)
                m.removeCoreference();
                mentions.set(i, m.markCoreferent(clusters.get(mentionHeadString)));
            } else {
                // not exact head match (or already in an exact string match cluster), so just keep it the way it is
                clusters.put(mentionHeadString, c.entity);
            }
        }
        return mentions;
    }

    public List<ClusteredMention> handlePronouns(List<ClusteredMention> mentions) {
        // get all the non-pronoun entities (clusters)
        Set<Entity> entities = new HashSet<Entity>();
        for (ClusteredMention c : mentions) {
            Mention m = c.mention;
            if (Pronoun.valueOrNull(m.gloss().toLowerCase()) == null) {
                entities.add(c.entity);
            }
        }

        // go through all the pronouns
        for (int i = 0; i < mentions.size(); i++) {
            ClusteredMention c = mentions.get(i);
            Mention m = c.mention;

            // if not a pronoun, skip
            if (Pronoun.valueOrNull(m.gloss().toLowerCase()) != null) {
                // get the NER, tense, gender
                Pronoun currPronoun = Pronoun.valueOrNull(m.gloss().toLowerCase());

                boolean isPlural = currPronoun.plural;
                Gender pronounGender = currPronoun.gender;
                Pronoun.Speaker pronounSpeaker = currPronoun.speaker;

                Entity bestEntity = null;
                int entityWithClosestMentionDistance = Integer.MAX_VALUE;

                for (Entity e : entities) {
                    Mention closestCandidateMention = null;
                    int closestCandidateMentionDistance = Integer.MAX_VALUE;
                    boolean allFitCriteria = true; // make sure that each of the entity clusters satisfies the criteria for this given pronoun

                    // go through each mention in each entity cluster
                    for (Mention candidateMention : e.mentions) {
                        String currMentionHeadLemma = candidateMention.sentence.lemmas.get(candidateMention.headWordIndex);

                        boolean isCandidatePlural = isPlural(candidateMention.sentence.posTags.get(candidateMention.headWordIndex));
                        Gender candidateGender = null;
                        if (gender.containsKey(currMentionHeadLemma)) {
                            candidateGender = gender.get(currMentionHeadLemma);
                        }

                        Pronoun.Speaker candidateSpeaker = null;
                        if (speaker.containsKey(currMentionHeadLemma)) {
                            candidateSpeaker = speaker.get(currMentionHeadLemma);
                        }

                        //                        System.out.println("<<<<<");
                        //                        
                        //                        System.out.println("candidate speaker: " + candidateSpeaker);
                        //                        System.out.println("candidate gender: " + candidateGender);
                        //                        System.out.println("candidate plural: " + isCandidatePlural);
                        //                        
                        //                        System.out.println("pronoun speaker: " + pronounSpeaker);
                        //                        System.out.println("pronoun gender: " + pronounGender);
                        //                        System.out.println("pronoun plural: " + isPlural);
                        //                        
                        //                        System.out.println(">>>>>>");

                        //                        if (candidateSpeaker != null) {
                        //                            System.out.println("candidate speaker: " + candidateSpeaker);
                        //                            System.out.println("candidate gender: " + candidateGender);
                        //                            System.out.println("candidate plural: " + isCandidatePlural);
                        //                        }

                        // compare the pronoun and the current mention
                        if ((isCandidatePlural == isPlural && pronounGender == candidateGender && pronounSpeaker == candidateSpeaker) || 
                                (candidateSpeaker == null || candidateGender == null)) {
                            // record the distance between the pronoun and this mention
                            int distance;
                            if (m.beginIndexInclusive > candidateMention.endIndexExclusive) {
                                distance = m.beginIndexInclusive - candidateMention.endIndexExclusive;
                            } else {
                                distance = candidateMention.beginIndexInclusive - m.endIndexExclusive;
                            }

                            //System.out.println("Distance: " + distance);

                            if (distance < closestCandidateMentionDistance) {
                                closestCandidateMentionDistance = distance;
                                closestCandidateMention = candidateMention;
                            }
                        } else {
                            allFitCriteria = false;
                        }
                    }

                    if (allFitCriteria && closestCandidateMentionDistance < entityWithClosestMentionDistance) {
                        entityWithClosestMentionDistance = closestCandidateMentionDistance;
                        bestEntity = e;
                    }
                }

                if (bestEntity != null) {
                    // found the entity for this pronoun
                    m.removeCoreference();
                    mentions.set(i, m.markCoreferent(bestEntity));
                } else {
                    // this pronoun is coreferent with nothing...
                    //System.out.println("NULLLLLL");
                }
            }
        }

        return mentions;
    }

    private boolean isPlural(String posTag) {
        return (posTag.equals("NNS") || posTag.equals("NNPS"));
    }
}

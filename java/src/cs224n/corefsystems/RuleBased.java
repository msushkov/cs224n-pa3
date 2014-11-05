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

    // what gender are words in the training set?
    HashMap<String, Gender> gender = new HashMap<String, Gender>();
    
    // what is the speaker of words in the training set?
    HashMap<String, Pronoun.Speaker> speaker = new HashMap<String, Pronoun.Speaker>();

    // Collect gender and speaker statistics from the training set using the pronouns that words are coreferent with.
    // Look at words that are coreferent with pronouns and tally up the corresponding speaker/gender of that pronoun.
    // Then get the most frequent speaker/gender and say that this is the speaker/gender of the word.
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

                    // if m1 is a pronoun and m2 is not
                    if (Pronoun.valueOrNull(m1Text) != null && Pronoun.valueOrNull(m2Text) == null) {
                        Pronoun curr = Pronoun.valueOrNull(m1Text);
                        if (curr != null) {
                            String word = sent2.lemmas.get(m2.headWordIndex);
                            genderCounter.incrementCount(word.toLowerCase(), curr.gender, 1);
                            personCounter.incrementCount(word.toLowerCase(), curr.speaker, 1);    
                        }
                    // if m2 is a pronoun and m1 is not
                    } else if (Pronoun.valueOrNull(m2Text) != null && Pronoun.valueOrNull(m1Text) == null) {
                        Pronoun curr = Pronoun.valueOrNull(m2Text);
                        if (curr != null) {
                            String word = sent1.lemmas.get(m1.headWordIndex);
                            genderCounter.incrementCount(word.toLowerCase(), curr.gender, 1);
                            personCounter.incrementCount(word.toLowerCase(), curr.speaker, 1);
                        }
                    } else {
                        // skip: both m1 and m2 are pronouns, or neither is
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

        String I_PRONOUNS = "I_KEY"; // i, me, mine, my, myself
        Set<String> iPronounSet = new HashSet<String>();
        iPronounSet.add("i");
        iPronounSet.add("me");
        iPronounSet.add("mine");
        iPronounSet.add("my");
        iPronounSet.add("myself");
        
        String YOU_PRONOUNS = "YOU_KEY"; // you, yourself
        Set<String> youPronounSet = new HashSet<String>();
        youPronounSet.add("you");
        youPronounSet.add("yourself");

        // ignore pronouns
        for (Mention m : doc.getMentions()){
            String mentionString = m.gloss();

            // handle i-pronoun case
            if (iPronounSet.contains(mentionString.toLowerCase())) {
                if (clusters.containsKey(I_PRONOUNS)) {
                    mentions.add(m.markCoreferent(clusters.get(I_PRONOUNS)));
                } else {
                    ClusteredMention newCluster = m.markSingleton();
                    mentions.add(newCluster);
                    clusters.put(I_PRONOUNS, newCluster.entity);
                }
            } else if (youPronounSet.contains(mentionString.toLowerCase())) { // handle you-pronoun
                if (clusters.containsKey(YOU_PRONOUNS)) {
                    mentions.add(m.markCoreferent(clusters.get(YOU_PRONOUNS)));
                } else {
                    ClusteredMention newCluster = m.markSingleton();
                    mentions.add(newCluster);
                    clusters.put(YOU_PRONOUNS, newCluster.entity);
                }
            } else {
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
            if (Pronoun.valueOrNull(m.gloss()) != null) {
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
            if (Pronoun.valueOrNull(m.gloss()) == null) {
                entities.add(c.entity);
            }
        }

        // go through all the pronouns
        for (int i = 0; i < mentions.size(); i++) {
            ClusteredMention c = mentions.get(i);
            Mention m = c.mention;

            // if not a pronoun, skip
            if (Pronoun.valueOrNull(m.gloss()) != null) {
                // get the NER, tense, gender
                Pronoun currPronoun = Pronoun.valueOrNull(m.gloss());

                Entity bestEntity = null;
                int bestEntityScore = Integer.MIN_VALUE;

                for (Entity e : entities) {
                	int score = pronounEntityMatchScore(currPronoun, m, e);
                	if (score > bestEntityScore) {
                		bestEntityScore = score;
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

    // TODO: add NER-based rules for pronouns (he, she -> person, etc)
    
    private boolean isPlural(String posTag) {
        return (posTag.equals("NNS") || posTag.equals("NNPS"));
    }
    
    private int pronounEntityMatchScore(Pronoun p, Mention pronounMention, Entity e) {
    	int score = 0;
        int closestCandidateMentionDistance = Integer.MAX_VALUE;

        boolean isPlural = p.plural;
        Gender pronounGender = p.gender;
        Pronoun.Speaker pronounSpeaker = p.speaker;

        // go through each mention in each entity cluster
        for (Mention candidateMention : e.mentions) {
        	Pronoun candidatePronoun = Pronoun.valueOrNull(candidateMention.gloss());
        	
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
            
            if (isCandidatePlural == isPlural) {
            	score += 1;
            } else {
            	score -= 1;
            }
            
            if (candidateGender != null) {
            	if (pronounGender == candidateGender) {
            		score += 1;
            	} else {
            		score -= 1;
            	}
            }
            
            if (candidatePronoun != null && candidatePronoun.equals(p)) {
            	score += 3;
            }
            
            if (candidateSpeaker != null) {
            	if (pronounSpeaker == candidateSpeaker) {
            		score += 1;
            	} else {
            		score -= 1;
            	}
            }


            int distance;
            if (pronounMention.beginIndexInclusive > candidateMention.endIndexExclusive) {
                distance = pronounMention.beginIndexInclusive - candidateMention.endIndexExclusive;
            } else {
                distance = candidateMention.beginIndexInclusive - pronounMention.endIndexExclusive;
            }

            if (distance < closestCandidateMentionDistance) {
                closestCandidateMentionDistance = distance;
            }
        }
        
        score -= .5 * closestCandidateMentionDistance;
    	return score;
    }
}

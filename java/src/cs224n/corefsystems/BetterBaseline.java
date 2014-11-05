package cs224n.corefsystems;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

import cs224n.coref.*;
import cs224n.util.CounterMap;
import cs224n.util.Pair;

public class BetterBaseline implements CoreferenceSystem {

    // counts words that are coreferent
    CounterMap<String, String> counts = new CounterMap<String, String>();
    int MIN_COUNT = 5;

    @Override
    public void train(Collection<Pair<Document, List<Entity>>> trainingData) {
        // count how often pairs of words are coreferent
        for (Pair<Document, List<Entity>> point : trainingData) {
            Document doc = point.getFirst();
            for (Entity e : point.getSecond()) {
                for (Pair<Mention, Mention> pair : e.orderedMentionPairs()) {
                    Mention m1 = pair.getFirst();
                    Mention m2 = pair.getSecond();
                    Sentence sent1 = m1.sentence;
                    Sentence sent2 = m2.sentence;
                    String m1Text = sent1.lemmas.get(m1.headWordIndex);
                    String m2Text = sent2.lemmas.get(m2.headWordIndex);
                    counts.incrementCount(m1Text, m2Text, 1);
                    counts.incrementCount(m2Text, m1Text, 1);
                }
            }
        }
    }

    
    @Override
    public List<ClusteredMention> runCoreference(Document doc) {
        ArrayList<ClusteredMention> clusters = new ArrayList<ClusteredMention>();
        ArrayList<Entity> entities = new ArrayList<Entity>();

        // if this mention is not coreferent with any of our existing clusters, make it its own cluster
        for (Mention m : doc.getMentions()) {
            // go through all the entities; if find a match, then add it to that cluster; otherwise, make it its own
            boolean isMatch = false;
            for (Entity e : entities) {
                if (checkClusterCoref(m, e)) {
                    clusters.add(m.markCoreferent(e));
                    isMatch = true;
                    break;
                }
            }
            if (!isMatch) {
                ClusteredMention newCluster = m.markSingleton();
                clusters.add(newCluster);
                entities.add(newCluster.entity);
            }
        }
        return clusters;
    }

    // Checks if given mention is coreferent with all members of the cluster.
    // Coreferent here means that either the head lemmas match, or the word pair has a significant count in the training set
    public boolean checkClusterCoref(Mention testMention, Entity e) {
        String testHeadLemma = testMention.sentence.lemmas.get(testMention.headWordIndex);

        for (Mention m : e.mentions) {
            String currHeadLemma = m.sentence.lemmas.get(m.headWordIndex);
            Double c1 = counts.getCount(testHeadLemma, currHeadLemma);
            Double c2 = counts.getCount(currHeadLemma, testHeadLemma);

            if (!(currHeadLemma.equals(testHeadLemma) || c1 > MIN_COUNT || c2 > MIN_COUNT)) {
                return false;
            }
        }
        return true;
    }
}

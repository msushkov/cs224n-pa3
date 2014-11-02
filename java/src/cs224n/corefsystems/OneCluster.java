package cs224n.corefsystems;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import cs224n.coref.ClusteredMention;
import cs224n.coref.Document;
import cs224n.coref.Entity;
import cs224n.coref.Mention;
import cs224n.util.Pair;

public class OneCluster implements CoreferenceSystem {

	@Override
	public void train(Collection<Pair<Document, List<Entity>>> trainingData) {
		// TODO Auto-generated method stub

	}

	@Override
	public List<ClusteredMention> runCoreference(Document doc) {
		// TODO Auto-generated method stub
		Entity e = null;
		List<ClusteredMention> clusteredMentions = new ArrayList<ClusteredMention>();
		for (Mention m : doc.getMentions()) {
			if (e == null) {
				ClusteredMention c = m.markSingleton();
				e = c.entity;
				clusteredMentions.add(c);
			} else {
				clusteredMentions.add(m.markCoreferent(e));
			}
		}
		return clusteredMentions;
	}

}

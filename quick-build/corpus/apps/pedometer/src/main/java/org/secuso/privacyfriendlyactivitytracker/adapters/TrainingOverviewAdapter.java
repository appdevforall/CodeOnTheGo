package org.secuso.privacyfriendlyactivitytracker.adapters;

/**
 * Scaffolding stand-in: the real TrainingOverviewAdapter (outside this subgraph pin) is a
 * RecyclerView.Adapter driving three card layouts full of icon drawables -- pulling those in
 * for one constant field wasn't worth the extra classpath/resource surface. Training.java (in
 * the subgraph) only reads VIEW_TYPE_TRAINING_SESSION.
 */
public class TrainingOverviewAdapter {
	public static final int VIEW_TYPE_TRAINING_SESSION = 0;
}

package app.payload.data;

/** One survey question, parsed from assets/questions.json. Immutable. */
public final class Question {

    public final String id;
    public final String category;
    public final String text;

    public Question(String id, String category, String text) {
        this.id = id;
        this.category = category;
        this.text = text;
    }
}

package ch.threema.storage.models.poll;


import java.time.Instant;

public class PollModel {
    public static final String TABLE = "ballot";
    public static final String COLUMN_ID = "id";
    public static final String COLUMN_API_POLL_ID = "apiBallotId";
    public static final String COLUMN_CREATOR_IDENTITY = "creatorIdentity";
    public static final String COLUMN_NAME = "name";
    public static final String COLUMN_STATE = "state";
    public static final String COLUMN_ASSESSMENT = "assessment";
    public static final String COLUMN_TYPE = "type";
    public static final String COLUMN_CHOICE_TYPE = "choiceType";
    public static final String COLUMN_DISPLAY_TYPE = "displayType";
    public static final String COLUMN_CREATED_AT = "createdAt";
    public static final String COLUMN_MODIFIED_AT = "modifiedAt";
    public static final String COLUMN_LAST_VIEWED_AT = "lastViewedAt";

    public enum State {
        TEMPORARY, OPEN, CLOSED
    }

    public enum Assessment {
        SINGLE_CHOICE, MULTIPLE_CHOICE
    }

    public enum Type {
        RESULT_ON_CLOSE, INTERMEDIATE
    }

    public enum ChoiceType {
        TEXT
    }

    public enum DisplayType {
        // CHECKLIST is F1Whisper-only; it rides the existing Ballot/Poll wire and is persisted by
        // name here. See BallotData.DisplayType (domain) for the interop caveat.
        LIST_MODE, SUMMARY_MODE, CHECKLIST
    }

    private int id;
    private String apiPollId;
    private String creatorIdentity;
    private String name;
    private State state;
    private Assessment assessment;
    private Type type;
    private ChoiceType choiceType;
    private DisplayType displayType;
    private Instant createdAt;
    private Instant modifiedAt;
    private Instant lastViewedAt;

    public int getId() {
        return id;
    }

    public PollModel setId(int id) {
        this.id = id;
        return this;
    }

    public String getApiPollId() {
        return apiPollId;
    }

    public PollModel setApiPollId(String apiPollId) {
        this.apiPollId = apiPollId;
        return this;
    }

    public String getCreatorIdentity() {
        return creatorIdentity;
    }

    public PollModel setCreatorIdentity(String creatorIdentity) {
        this.creatorIdentity = creatorIdentity;
        return this;
    }

    public String getName() {
        return name;
    }

    public PollModel setName(String name) {
        this.name = name;
        return this;
    }

    public State getState() {
        return state;
    }

    public PollModel setState(State state) {
        this.state = state;
        return this;
    }

    public Assessment getAssessment() {
        return assessment;
    }

    public PollModel setAssessment(Assessment assessment) {
        this.assessment = assessment;
        return this;
    }

    public Type getType() {
        return this.type;
    }

    public PollModel setType(Type type) {
        this.type = type;
        return this;
    }

    public ChoiceType getChoiceType() {
        return choiceType;
    }

    public PollModel setChoiceType(ChoiceType choiceType) {
        this.choiceType = choiceType;
        return this;
    }

    public DisplayType getDisplayType() {
        return displayType;
    }

    public PollModel setDisplayType(DisplayType displayType) {
        this.displayType = displayType;
        return this;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public PollModel setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
        return this;
    }

    public Instant getModifiedAt() {
        return modifiedAt;
    }

    public PollModel setModifiedAt(Instant modifiedAt) {
        this.modifiedAt = modifiedAt;
        return this;
    }

    public Instant getLastViewedAt() {
        return this.lastViewedAt;
    }

    public PollModel setLastViewedAt(Instant lastViewedAt) {
        this.lastViewedAt = lastViewedAt;
        return this;
    }

}

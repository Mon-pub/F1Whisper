package ch.threema.domain.protocol.csp.messages.poll;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import ch.threema.domain.protocol.csp.messages.BadMessageException;

public class PollDataTest {
    private static final String testPoll = "{"
        + "\"d\":\"Polltelli\","
        + "\"s\":0,"
        + "\"a\":0,"
        + "\"t\":1,"
        + "\"o\":0,"
        + "\"c\":["
        + "{"
        + "\"i\":1,"
        + "\"n\":\"desc1\","
        + "\"o\":1,"
        + "\"r\":[1,0],"
        + "\"t\":2"
        + "},"
        + "{"
        + "\"i\":2,"
        + "\"n\":\"desc2\","
        + "\"o\":2,"
        + "\"r\":[1,1],"
        + "\"t\":2"
        + "}"
        + "]," +
        "\"p\":[\"ECHOECH1\",\"ECHOECH2\"],"
        + "\"u\":0"
        + "}";

    @Test
    public void parseValidString() {
        PollData result = null;
        try {
            result = PollData.parse(testPoll);
        } catch (BadMessageException e) {
            Assertions.fail(e.getMessage());
        }
        Assertions.assertNotNull(result);

        Assertions.assertEquals("Polltelli", result.getDescription());

        Assertions.assertEquals(2, result.getChoiceList().size());

        Assertions.assertEquals(PollData.State.OPEN, result.getState());
        Assertions.assertEquals(PollData.AssessmentType.SINGLE, result.getAssessmentType());
        Assertions.assertEquals(PollData.Type.INTERMEDIATE, result.getType());
        Assertions.assertEquals(PollData.ChoiceType.TEXT, result.getChoiceType());
        Assertions.assertEquals(2, result.getParticipants().size());
        Assertions.assertEquals("ECHOECH2", result.getParticipants().get(1));
    }


    @Test
    public void parseInvalidString() {
        try {
            PollData.parse("i want to be a hippie");
            Assertions.fail("invalid string parsed");
        } catch (BadMessageException e) {
            //ok! exception received
        }
    }

    /**
     * F1Whisper: a ballot with displayType=2 (the checklist discriminator) must round-trip to
     * {@link PollData.DisplayType#CHECKLIST}.
     */
    @Test
    public void parseChecklistDisplayType() {
        String checklistBallot = testPoll.replace("\"u\":0", "\"u\":2");
        try {
            PollData result = PollData.parse(checklistBallot);
            Assertions.assertNotNull(result);
            Assertions.assertEquals(PollData.DisplayType.CHECKLIST, result.getDisplayType());
        } catch (BadMessageException e) {
            Assertions.fail(e.getMessage());
        }
    }

    /**
     * F1Whisper forward-compat: an UNKNOWN displayType id must NOT throw -- it defaults to
     * {@link PollData.DisplayType#LIST_MODE} so an otherwise valid ballot is never dropped.
     */
    @Test
    public void parseUnknownDisplayTypeDefaultsToListMode() {
        String unknownDisplayTypeBallot = testPoll.replace("\"u\":0", "\"u\":99");
        try {
            PollData result = PollData.parse(unknownDisplayTypeBallot);
            Assertions.assertNotNull(result);
            Assertions.assertEquals(PollData.DisplayType.LIST_MODE, result.getDisplayType());
        } catch (BadMessageException e) {
            Assertions.fail("unknown displayType must not throw: " + e.getMessage());
        }
    }

    @Test
    public void generateStringTest() {
        PollData d = new PollData();
        d.setDescription("Polltelli");
        d.setState(PollData.State.OPEN);
        d.setAssessmentType(PollData.AssessmentType.SINGLE);
        d.setType(PollData.Type.INTERMEDIATE);
        d.setChoiceType(PollData.ChoiceType.TEXT);
        int posEcho1 = d.addParticipant("ECHOECH1");
        Assertions.assertEquals(0, posEcho1);
        int posEcho2 = d.addParticipant("ECHOECH2");
        Assertions.assertEquals(1, posEcho2);


        PollDataChoice c1 = new PollDataChoice(2);
        c1.setId(1);
        c1.setName("desc1");
        c1.setOrder(1);
        c1.addResult(0, 1).addResult(1, 0);
        c1.setTotalVotes(2);
        d.getChoiceList().add(c1);

        PollDataChoice c2 = new PollDataChoice(2);
        c2.setId(2);
        c2.setOrder(2);
        c2.setName("desc2");
        c2.addResult(0, 1).addResult(1, 1);
        c2.setTotalVotes(2);
        d.getChoiceList().add(c2);
        d.setDisplayType(PollData.DisplayType.LIST_MODE);

        try {
            PollData b = PollData.parse(testPoll);
            Assertions.assertEquals(b.generateString(), d.generateString());
        } catch (BadMessageException e) {
            Assertions.fail(e.getMessage());
        }
    }
}

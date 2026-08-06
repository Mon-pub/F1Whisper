package ch.threema.domain.protocol.csp.messages.fs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import ch.threema.domain.protocol.csp.ProtocolDefines;
import ch.threema.domain.protocol.csp.messages.AbstractMessage;
import ch.threema.domain.protocol.csp.messages.protobuf.AbstractProtobufMessage;
import ch.threema.protobuf.csp.e2e.fs.Version;

public class ForwardSecurityEnvelopeMessage extends AbstractProtobufMessage<ForwardSecurityData> {

    @Nullable
    private final AbstractMessage innerMessage;

    private final boolean isForwardSecurityControlMessage;

    /**
     * Use this constructor for incoming forward security envelope messages.
     *
     * @param payloadData the forward security payload
     */
    public ForwardSecurityEnvelopeMessage(@NonNull ForwardSecurityData payloadData) {
        this(payloadData, false);
    }

    public ForwardSecurityEnvelopeMessage(@NonNull ForwardSecurityData payloadData, boolean isForwardSecurityControlMessage) {
        super(ProtocolDefines.MSGTYPE_FS_ENVELOPE, payloadData);
        this.innerMessage = null;
        this.isForwardSecurityControlMessage = isForwardSecurityControlMessage;
    }

    /**
     * Use this for outgoing forward security envelope messages. The inner message is used to set
     * message flags and type specific properties.
     *
     * @param payloadData         the forward security payload
     * @param innerMessage        the inner message
     * @param forwardSecurityMode the forward security mode
     */
    public ForwardSecurityEnvelopeMessage(
        @NonNull ForwardSecurityData payloadData,
        @NonNull AbstractMessage innerMessage,
        @NonNull ForwardSecurityMode forwardSecurityMode
    ) {
        super(ProtocolDefines.MSGTYPE_FS_ENVELOPE, payloadData);
        this.innerMessage = innerMessage;
        this.isForwardSecurityControlMessage = false;

        setFromIdentity(innerMessage.getFromIdentity());
        setToIdentity(innerMessage.getToIdentity());
        setMessageId(innerMessage.getMessageId());
        setDate(innerMessage.getDate());
        setMessageFlags(innerMessage.getMessageFlags());
        setNickname(innerMessage.getNickname());
        setForwardSecurityMode(forwardSecurityMode);
    }

    /**
     * F1Whisper: the metadata box is built from the envelope, not from the inner message, so the
     * envelope has to report the inner message's disappearing-messages timer or the field would be
     * written for nobody, because nearly all real 1:1 traffic is FS-encapsulated.
     * <p>
     * This delegates rather than copying in the constructor, so the envelope cannot go stale if
     * the inner message is stamped after the envelope was built.
     * <p>
     * Unlike {@link #allowUserProfileDistribution()} this must not throw when there is no inner
     * message: an <i>incoming</i> envelope is decoded first and its timer is set from the metadata
     * box by {@code MessageCoder.decode()}, then copied inward by
     * {@code MessageCoder.decodeEncapsulated()}. In that direction the envelope's own field is the
     * value, so fall through to it.
     */
    @Nullable
    @Override
    public Integer getDisappearingTimerSeconds() {
        if (innerMessage != null) {
            return innerMessage.getDisappearingTimerSeconds();
        }
        return super.getDisappearingTimerSeconds();
    }

    @Nullable
    @Override
    public Version getMinimumRequiredForwardSecurityVersion() {
        // Do not allow encapsulating forward security envelope messages
        return null;
    }

    @Override
    public boolean allowUserProfileDistribution() {
        if (isForwardSecurityControlMessage) {
            return false;
        }
        if (innerMessage == null) {
            throw new IllegalStateException("Cannot check for user profile distribution on incoming fs envelopes");
        }
        return innerMessage.allowUserProfileDistribution();
    }

    @Override
    public boolean exemptFromBlocking() {
        // Note that checking for exemption from blocking should never happen on forward security
        // envelope messages.
        throw new IllegalStateException("Cannot check for exemption from blocking of fs envelopes");
    }

    @Override
    public boolean createImplicitlyDirectContact() {
        // Note that checking for implicit direct contact creation must never happen on forward
        // security envelope messages.
        throw new IllegalStateException("Cannot check for implicit direct contact creation on fs envelopes");
    }

    @Override
    public boolean protectAgainstReplay() {
        if (isForwardSecurityControlMessage) {
            return true;
        }
        if (innerMessage == null) {
            throw new IllegalStateException("Cannot check for replay protection on incoming fs envelopes");
        }
        return innerMessage.protectAgainstReplay();
    }

    @Override
    public boolean reflectIncoming() {
        throw new IllegalStateException("Cannot check incoming reflection of incoming fs envelopes before decryption");
    }

    @Override
    public boolean reflectOutgoing() {
        if (innerMessage == null) {
            throw new IllegalStateException("Cannot check outgoing reflection of incoming fs envelopes");
        }
        return false;
    }

    @Override
    public boolean reflectSentUpdate() {
        if (innerMessage == null) {
            throw new IllegalStateException("Cannot check sent update reflection of incoming fs envelopes");
        }
        return innerMessage.reflectSentUpdate();
    }

    @Override
    public boolean sendAutomaticDeliveryReceipt() {
        throw new IllegalStateException("Cannot check for sending automatic delivery receipt on fs envelopes");
    }

    @Override
    public boolean bumpLastUpdate() {
        throw new IllegalStateException("Cannot check bumpLastUpdate on fs envelopes");
    }

    @Override
    public boolean flagSendPush() {
        // Note that a forward security envelope message initially has no flags set
        return false;
    }

    @Override
    public boolean flagNoServerQueuing() {
        // Note that a forward security envelope message initially has no flags set
        return false;
    }

    @Override
    public boolean flagNoServerAck() {
        // Note that a forward security envelope message initially has no flags set
        return false;
    }

    @Override
    public boolean flagGroupMessage() {
        // Note that a forward security envelope message initially has no flags set
        return false;
    }

    @Override
    public boolean flagShortLivedServerQueuing() {
        // Note that a forward security envelope message initially has no flags set
        return false;
    }

}

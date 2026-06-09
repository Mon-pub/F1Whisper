package ch.threema.app.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import ch.threema.app.BuildConfig;
import ch.threema.app.R;
import ch.threema.app.availabilitystatus.AvailabilityStatusIconElevatedView;
import ch.threema.data.datatypes.AvailabilityStatus;

public class InitialAvatarView extends FrameLayout {
    private TextView avatarInitials;
    private @Nullable AvailabilityStatusIconElevatedView badgeAvailabilityStatus;

    public InitialAvatarView(Context context) {
        super(context);
        init(context);
    }

    public InitialAvatarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public InitialAvatarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(@NonNull Context context) {
        LayoutInflater inflater = (LayoutInflater) context
            .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.initial_avatar_view, this);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        avatarInitials = this.findViewById(R.id.avatar_initials);

        if (BuildConfig.AVAILABILITY_STATUS_ENABLED) {
            badgeAvailabilityStatus = this.findViewById(R.id.avatar_badge_availability_status);
            badgeAvailabilityStatus.setVisibility(GONE);
        }
    }

    public void setInitials(String firstName, String lastName) {
        StringBuilder initialsBuilder = new StringBuilder();
        if (firstName != null && !firstName.isEmpty()) {
            initialsBuilder.append(firstName.substring(0, 1));
        }
        if (lastName != null && !lastName.isEmpty()) {
            initialsBuilder.append(lastName.substring(0, 1));
        }

        avatarInitials.setText(initialsBuilder.length() > 0 ? initialsBuilder.toString() : "");
        requestLayout();
    }

    public void setAvailabilityStatusBadgeState(@Nullable AvailabilityStatus.Set availabilityStatusSet) {
        if (!BuildConfig.AVAILABILITY_STATUS_ENABLED || badgeAvailabilityStatus == null) {
            return;
        }
        badgeAvailabilityStatus.setVisibility(availabilityStatusSet != null ? VISIBLE : GONE);
        badgeAvailabilityStatus.setStatus(availabilityStatusSet);
    }
}

package ch.threema.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import ch.threema.app.BuildConfig
import ch.threema.app.R
import ch.threema.app.availabilitystatus.AvailabilityStatusIconElevatedView
import ch.threema.data.datatypes.AvailabilityStatus

class AvatarView : FrameLayout {
    private lateinit var avatar: ImageView
    private lateinit var badgeWork: ImageView
    private var badgeAvailabilityStatusContainer: FrameLayout? = null
    private var badgeAvailabilityStatus: AvailabilityStatusIconElevatedView? = null

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    init {
        context.getSystemService<LayoutInflater>()!!.inflate(R.layout.avatar_view, this)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()

        avatar = findViewById(R.id.avatar)
        badgeWork = findViewById(R.id.avatar_badge_work)
        badgeWork.isVisible = false

        @Suppress("KotlinConstantConditions")
        if (BuildConfig.AVAILABILITY_STATUS_ENABLED) {
            // AvailabilityStatusIconElevatedView is a ComposeView. For it to not cause a crash when used inside a popup window such as the
            // MentionSelectorPopup, we first need to set the view tree lifecycle owner before we can add the view itself.
            badgeAvailabilityStatus = AvailabilityStatusIconElevatedView(context)
            badgeAvailabilityStatusContainer = findViewById<FrameLayout>(R.id.avatar_badge_availability_status_container)?.apply {
                id = android.R.id.content
                setViewTreeLifecycleOwner(context as LifecycleOwner)
                setViewTreeSavedStateRegistryOwner(context as SavedStateRegistryOwner)
                addView(badgeAvailabilityStatus)
            }
        }
    }

    fun setImageResource(@DrawableRes resource: Int) {
        avatar.setImageResource(resource)
        avatar.requestLayout()
    }

    fun setImageBitmap(bitmap: Bitmap?) {
        avatar.setImageBitmap(bitmap)
        avatar.requestLayout()
    }

    fun setImageDrawable(drawable: Drawable?) {
        avatar.setImageDrawable(drawable)
        avatar.requestLayout()
    }

    /**
     * This returns the avatar image view. This is mainly needed for glide to directly set the avatars.
     *
     * @return the image view of the avatar drawable
     */
    val avatarView: ImageView
        get() = avatar

    fun setWorkBadgeVisible(visible: Boolean) {
        badgeWork.setVisibility(if (visible) VISIBLE else GONE)
    }

    fun setAvailabilityStatusBadgeState(availabilityStatusSet: AvailabilityStatus.Set?) {
        @Suppress("KotlinConstantConditions")
        if (!BuildConfig.AVAILABILITY_STATUS_ENABLED) {
            return
        }
        badgeAvailabilityStatus?.setStatus(availabilityStatusSet)
        badgeAvailabilityStatusContainer?.isVisible = availabilityStatusSet != null
    }
}

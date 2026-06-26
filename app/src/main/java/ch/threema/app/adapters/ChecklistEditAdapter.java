package ch.threema.app.adapters;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import ch.threema.app.R;
import ch.threema.storage.models.ballot.BallotChoiceModel;

/**
 * F1Whisper: backing adapter for the "Edit checklist" dialog's {@link RecyclerView}. Hosts the
 * working copy of the checklist's choices and supports a REAL long-press drag reorder (via an
 * {@link ItemTouchHelper} the host attaches), plus per-row rename and delete. The in-bubble checklist
 * lives inside a ListView row where an ItemTouchHelper cannot function, so reordering is done here in a
 * dedicated dialog and applied back as a re-broadcast on confirm.
 *
 * <p>The adapter mutates its own working list in place; the host reads {@link #getChoices()} on Apply.
 * It does NOT touch the database or the wire -- persistence + re-broadcast happen in the host once the
 * user confirms.
 */
public class ChecklistEditAdapter extends RecyclerView.Adapter<ChecklistEditAdapter.ChoiceViewHolder> {

    /**
     * Callbacks for the host (the checklist decorator) so it can react to per-row edits.
     */
    public interface Callbacks {
        /**
         * The user tapped a row's name to rename it. The host shows an input dialog and, on confirm,
         * calls {@link ChecklistEditAdapter#renameAt(int, String)}.
         */
        void onRenameRequested(int position, @NonNull BallotChoiceModel choice);

        /**
         * Begin dragging the row at this view holder. The host starts the {@link ItemTouchHelper} drag.
         */
        void onStartDrag(@NonNull RecyclerView.ViewHolder viewHolder);
    }

    @NonNull
    private final List<BallotChoiceModel> choices;
    @NonNull
    private final Callbacks callbacks;

    public ChecklistEditAdapter(@NonNull List<BallotChoiceModel> choices, @NonNull Callbacks callbacks) {
        this.choices = choices;
        this.callbacks = callbacks;
        setHasStableIds(false);
    }

    @NonNull
    public List<BallotChoiceModel> getChoices() {
        return choices;
    }

    /**
     * Reorder the working list to mirror a drag from {@code fromPosition} to {@code toPosition}.
     * Mirrors the proven adjacent-swap walk used by the poll wizard so a multi-row drag composes from
     * single steps and {@link #notifyItemMoved} animates smoothly.
     */
    public void moveItem(int fromPosition, int toPosition) {
        if (fromPosition < 0 || toPosition < 0
            || fromPosition >= choices.size() || toPosition >= choices.size()) {
            return;
        }
        if (fromPosition < toPosition) {
            for (int i = fromPosition; i < toPosition; i++) {
                Collections.swap(choices, i, i + 1);
            }
        } else {
            for (int i = fromPosition; i > toPosition; i--) {
                Collections.swap(choices, i, i - 1);
            }
        }
        notifyItemMoved(fromPosition, toPosition);
    }

    public void renameAt(int position, @NonNull String newName) {
        if (position < 0 || position >= choices.size()) {
            return;
        }
        choices.get(position).setName(newName);
        notifyItemChanged(position);
    }

    public void removeAt(int position) {
        if (position < 0 || position >= choices.size()) {
            return;
        }
        choices.remove(position);
        notifyItemRemoved(position);
    }

    public void addItem(@NonNull BallotChoiceModel choice) {
        choices.add(choice);
        notifyItemInserted(choices.size() - 1);
    }

    @NonNull
    @Override
    public ChoiceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_checklist_edit_row, parent, false);
        return new ChoiceViewHolder(view);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onBindViewHolder(@NonNull ChoiceViewHolder holder, int position) {
        final BallotChoiceModel choice = choices.get(position);
        holder.nameView.setText(choice.getName());

        holder.nameView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                callbacks.onRenameRequested(pos, choices.get(pos));
            }
        });

        holder.deleteButton.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                removeAt(pos);
            }
        });

        // Start a real drag as soon as the user touches the reorder handle (no long-press wait), which
        // is the most discoverable, reliable reorder gesture inside a dialog RecyclerView.
        holder.dragHandle.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                callbacks.onStartDrag(holder);
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return choices.size();
    }

    static class ChoiceViewHolder extends RecyclerView.ViewHolder {
        final ImageView dragHandle;
        final TextView nameView;
        final ImageView deleteButton;

        ChoiceViewHolder(@NonNull View itemView) {
            super(itemView);
            dragHandle = itemView.findViewById(R.id.checklist_edit_drag_handle);
            nameView = itemView.findViewById(R.id.checklist_edit_name);
            deleteButton = itemView.findViewById(R.id.checklist_edit_delete);
        }
    }
}

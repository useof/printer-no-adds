package com.noads.printer.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.noads.printer.R;
import com.noads.printer.model.Printer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Renders the printer list, highlighting the one currently selected. */
public final class PrinterAdapter extends RecyclerView.Adapter<PrinterAdapter.PrinterViewHolder> {

    public interface Listener {
        void onPrinterClicked(@NonNull Printer printer);

        void onPrinterLongClicked(@NonNull Printer printer);
    }

    private final List<Printer> printers = new ArrayList<>();
    private final Listener listener;

    @Nullable
    private String selectedUri;

    public PrinterAdapter(@NonNull Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void submit(@NonNull List<Printer> updated, @Nullable String selectedUri) {
        String previousSelection = this.selectedUri;
        this.selectedUri = selectedUri;

        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return printers.size();
            }

            @Override
            public int getNewListSize() {
                return updated.size();
            }

            @Override
            public boolean areItemsTheSame(int oldPos, int newPos) {
                return printers.get(oldPos).uri.equals(updated.get(newPos).uri);
            }

            @Override
            public boolean areContentsTheSame(int oldPos, int newPos) {
                Printer a = printers.get(oldPos);
                Printer b = updated.get(newPos);
                boolean selectionUnchanged =
                        Objects.equals(previousSelection, a.uri) == Objects.equals(selectedUri, b.uri);
                return selectionUnchanged
                        && a.name.equals(b.name)
                        && a.subtitle().equals(b.subtitle());
            }
        });

        printers.clear();
        printers.addAll(updated);
        diff.dispatchUpdatesTo(this);
    }

    @Override
    public long getItemId(int position) {
        return printers.get(position).uri.hashCode();
    }

    @NonNull
    @Override
    public PrinterViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_printer, parent, false);
        return new PrinterViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PrinterViewHolder holder, int position) {
        holder.bind(printers.get(position));
    }

    @Override
    public int getItemCount() {
        return printers.size();
    }

    final class PrinterViewHolder extends RecyclerView.ViewHolder {

        private final TextView nameView;
        private final TextView subtitleView;
        private final ImageView iconView;
        private final ImageView selectedMark;
        private final ImageView secureMark;

        PrinterViewHolder(@NonNull View itemView) {
            super(itemView);
            nameView = itemView.findViewById(R.id.printer_name);
            subtitleView = itemView.findViewById(R.id.printer_subtitle);
            iconView = itemView.findViewById(R.id.printer_icon);
            selectedMark = itemView.findViewById(R.id.printer_selected_mark);
            secureMark = itemView.findViewById(R.id.printer_secure_mark);
        }

        void bind(@NonNull Printer printer) {
            nameView.setText(printer.name);
            subtitleView.setText(printer.subtitle());

            boolean selected = printer.uri.equals(selectedUri);
            selectedMark.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            itemView.setActivated(selected);

            secureMark.setVisibility(printer.isSecure() ? View.VISIBLE : View.GONE);
            iconView.setImageResource(printer.manual
                    ? R.drawable.ic_printer_manual
                    : R.drawable.ic_printer);

            itemView.setOnClickListener(v -> listener.onPrinterClicked(printer));
            itemView.setOnLongClickListener(v -> {
                listener.onPrinterLongClicked(printer);
                return true;
            });
        }
    }
}

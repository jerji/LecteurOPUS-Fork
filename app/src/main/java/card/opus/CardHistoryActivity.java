package card.opus;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareUltralight;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcB;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import androidx.appcompat.app.AppCompatActivity;

public class CardHistoryActivity extends AppCompatActivity {

    private static final String TAG = "CardHistory";
    private ListView cardList;
    private TextView emptyText;
    private TextView cardCount;
    private CardDatabase db;
    private CardAdapter adapter;
    private boolean scanning = false;
    private android.app.AlertDialog scanDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_card_history);

        db = CardDatabase.getInstance(this);
        // Initialize file logging
        FileLog.init(this);

        cardList = findViewById(R.id.cardList);
        emptyText = findViewById(R.id.emptyText);
        cardCount = findViewById(R.id.cardCount);
        Button scanButton = findViewById(R.id.scanButton);
        Button importButton = findViewById(R.id.importButton);

        scanButton.setOnClickListener(v -> startScan());
        importButton.setOnClickListener(v -> showImportDialog());

        cardList.setOnItemClickListener((parent, view, position, id) -> {
            CardDatabase.CardEntry entry = (CardDatabase.CardEntry) adapter.getItem(position);
            openCard(entry);
        });

        refreshList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    private void refreshList() {
        List<CardDatabase.CardEntry> cards = db.getRecentCards(100);
        if (adapter == null) {
            adapter = new CardAdapter(cards);
            cardList.setAdapter(adapter);
        } else {
            adapter.replaceData(cards);
        }

        emptyText.setVisibility(cards.isEmpty() ? View.VISIBLE : View.GONE);
        cardList.setVisibility(cards.isEmpty() ? View.GONE : View.VISIBLE);
        cardCount.setText(cards.size() + " card" + (cards.size() != 1 ? "s" : ""));
    }

    private void startScan() {
        // Use the proven MainActivity + CardReader for scanning
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("returnResult", true);
        startActivityForResult(intent, 1002);
    }

    private void openCard(CardDatabase.CardEntry entry) {
        try {
            // Reconstruct Card from raw XML
            String rawXml = entry.rawXml;
            if (rawXml == null || rawXml.isEmpty()) {
                Toast.makeText(this, "Cannot open: no card data", Toast.LENGTH_SHORT).show();
                return;
            }

            Card card = Card.fromRawSerialize(rawXml);
            if (card == null) {
                FileLog.e(TAG, "openCard: fromRawSerialize returned null for " + rawXml.substring(0, Math.min(rawXml.length(), 80)));
                Toast.makeText(this, "Cannot open: invalid card data", Toast.LENGTH_SHORT).show();
                return;
            }

            Intent intent = new Intent(this, CardActivity.class);
            intent.putExtra("Card", card);
            intent.putExtra("entryId", entry.id);
            intent.putExtra("entryName", entry.getDisplayName());
            startActivity(intent);
        } catch (Exception e) {
            FileLog.e(TAG, "openCard error: " + e.getMessage(), e);
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void openCardFromCard(Card card) {
        Intent intent = new Intent(this, CardActivity.class);
        intent.putExtra("Card", card);
        // No entry info for freshly scanned cards — title falls back to card data
        startActivity(intent);
    }

    private void showImportDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Import Card");

        String[] options = {"Paste from clipboard", "Choose a file"};
        builder.setItems(options, (dialog, which) -> {
            if (which == 0) {
                // Paste from clipboard
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard.hasPrimaryClip()) {
                    CharSequence pasted = clipboard.getPrimaryClip().getItemAt(0).getText();
                    if (pasted != null) {
                        long id = db.importFromJson(pasted.toString());
                        if (id >= 0) {
                            Toast.makeText(this, "Card imported from clipboard!", Toast.LENGTH_SHORT).show();
                            refreshList();
                        } else {
                            Toast.makeText(this, "Clipboard doesn't contain valid card JSON", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show();
                }
            } else {
                // Choose a file
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/json");
                    startActivityForResult(intent, 1001);
                } catch (Exception e) {
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1002 && resultCode == RESULT_OK && data != null) {
            // Card scanned via MainActivity — save and open
            Card card = (Card) data.getSerializableExtra("Card");
            if (card != null) {
                db.saveCard(card);
                refreshList();
                openCardFromCard(card);
            }
        } else if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(getContentResolver().openInputStream(data.getData())));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                long id = db.importFromJson(sb.toString());
                if (id >= 0) {
                    Toast.makeText(this, "Card imported!", Toast.LENGTH_SHORT).show();
                    refreshList();
                } else {
                    Toast.makeText(this, "Invalid JSON format", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Import error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ---- Adapter ----
    private class CardAdapter extends BaseAdapter {
        private List<CardDatabase.CardEntry> items;
        private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

        CardAdapter(List<CardDatabase.CardEntry> items) {
            this.items = items;
        }

        void replaceData(List<CardDatabase.CardEntry> newItems) {
            this.items = newItems;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() { return items.size(); }

        @Override
        public Object getItem(int pos) { return items.get(pos); }

        @Override
        public long getItemId(int pos) { return items.get(pos).id; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_card_history, parent, false);
            }

            CardDatabase.CardEntry entry = items.get(pos);

            ImageView icon = v.findViewById(R.id.cardIcon);
            TextView name = v.findViewById(R.id.cardName);
            TextView detail = v.findViewById(R.id.cardDetail);

            // Set icon based on card type — use real card icons
            if ("OPUS".equals(entry.cardType)) {
                icon.setImageResource(R.mipmap.opus);
            } else {
                icon.setImageResource(R.drawable.ic_occasional_card);
            }

            // Card name
            name.setText(entry.getDisplayName());

            // Detail line: type + date + operator
            StringBuilder sb = new StringBuilder();
            sb.append(entry.cardType);
            sb.append(" · ").append(sdf.format(new Date(entry.scanTime)));
            if (entry.operatorId != 0) {
                sb.append(" · Op ").append(entry.operatorId);
            }
            detail.setText(sb.toString());

            // Arrow → context menu
            ImageView arrow = v.findViewById(R.id.arrowIcon);
            arrow.setOnClickListener(v1 -> showCardMenu(entry, v1));

            return v;
        }
    }

    private void showCardMenu(CardDatabase.CardEntry entry, View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "Rename");
        popup.getMenu().add(0, 2, 0, "Export JSON");
        popup.getMenu().add(0, 3, 0, "Copy JSON");
        popup.getMenu().add(0, 4, 0, "Duplicate");
        popup.getMenu().add(0, 5, 0, "Delete");

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: // Rename
                    renameCardDialog(entry);
                    return true;
                case 2: // Export JSON
                    exportCardJson(entry);
                    return true;
                case 3: // Copy JSON
                    copyCardJson(entry);
                    return true;
                case 4: // Duplicate
                    db.duplicateCard(entry.id);
                    refreshList();
                    Toast.makeText(this, "Card duplicated", Toast.LENGTH_SHORT).show();
                    return true;
                case 5: // Delete
                    deleteCardDialog(entry);
                    return true;
            }
            return false;
        });
        popup.show();
    }

    private void renameCardDialog(CardDatabase.CardEntry entry) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Rename Card");

        final EditText input = new EditText(this);
        input.setText(entry.productName);
        input.setSelectAllOnFocus(true);
        builder.setView(input);

        builder.setPositiveButton("Rename", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty()) {
                db.renameCard(entry.id, newName);
                refreshList();
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void deleteCardDialog(CardDatabase.CardEntry entry) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Delete Card");
        builder.setMessage("Delete \"" + entry.getDisplayName() + "\"?");
        builder.setPositiveButton("Delete", (dialog, which) -> {
            db.deleteCard(entry.id);
            refreshList();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void exportCardJson(CardDatabase.CardEntry entry) {
        try {
            Card card = Card.fromRawSerialize(entry.rawXml);
            if (card == null) {
                Toast.makeText(this, "Cannot export: invalid card data", Toast.LENGTH_SHORT).show();
                return;
            }
            String json = card.rawJson();
            String type = card.getType() == Card.CardType.OPUS ? "OPUS" : "MIFARE";
            String code = String.format("%04X", card.getProductCode());
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
            String filename = type + "_" + code + "_" + sdf.format(new Date()) + ".json";

            File path = getFilesDir();
            File jsonFile = new File(path, filename);
            FileOutputStream fos = new FileOutputStream(jsonFile);
            fos.write(json.getBytes());
            fos.close();

            Toast.makeText(this, "Saved: " + filename, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Export error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void copyCardJson(CardDatabase.CardEntry entry) {
        try {
            Card card = Card.fromRawSerialize(entry.rawXml);
            if (card == null) {
                Toast.makeText(this, "Cannot copy: invalid card data", Toast.LENGTH_SHORT).show();
                return;
            }
            String json = card.rawJson();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("card_json", json);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "JSON copied to clipboard", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Copy error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
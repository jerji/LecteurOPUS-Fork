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

        // Load product codes from XML
        Contract.loadProductCodes(this);

        db = CardDatabase.getInstance(this);
        // Initialize file logging
        FileLog.init(this);

        cardList = findViewById(R.id.cardList);
        emptyText = findViewById(R.id.emptyText);
        cardCount = findViewById(R.id.cardCount);
        Button scanButton = findViewById(R.id.scanButton);
        Button importButton = findViewById(R.id.importButton);

        scanButton.setOnClickListener(v -> startScan(false));
        scanButton.setOnLongClickListener(v -> {
            startScan(true);
            return true;
        });
        importButton.setOnClickListener(v -> showImportDialog());

        Button exportAllButton = findViewById(R.id.exportAllButton);
        exportAllButton.setOnClickListener(v -> showExportAllDialog());

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

    private void startScan(boolean fullEnumeration) {
        if (fullEnumeration) {
            // Show warning dialog before deep scan
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Deep Enumeration Scan");
            builder.setMessage("This will probe ~400 file IDs on the card.\n\n"
                    + "• Hold the card steady for ~8 seconds\n"
                    + "• Only works on OPUS cards (temp tickets scan normally)\n"
                    + "• Read-only commands — cannot modify the card\n\n"
                    + "Proceed?");
            builder.setPositiveButton("Start Scan", (dialog, which) -> {
                Intent intent = new Intent(this, MainActivity.class);
                intent.putExtra("returnResult", true);
                intent.putExtra("fullEnumeration", true);
                startActivityForResult(intent, 1002);
            });
            builder.setNegativeButton("Cancel", null);
            builder.show();
        } else {
            // Normal quick scan
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("returnResult", true);
            startActivityForResult(intent, 1002);
        }
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
                        int imported = importAll(pasted.toString());
                        if (imported > 0) {
                            Toast.makeText(this, imported + " card" + (imported != 1 ? "s" : "") + " imported from clipboard!", Toast.LENGTH_SHORT).show();
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

                String json = sb.toString().trim();
                int imported = importAll(json);
                if (imported > 0) {
                    Toast.makeText(this, imported + " card" + (imported != 1 ? "s" : "") + " imported!", Toast.LENGTH_SHORT).show();
                    refreshList();
                } else {
                    Toast.makeText(this, "Invalid JSON format", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Import error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private int importAll(String json) {
        int count = 0;
        try {
            json = json.trim();
            if (json.startsWith("[")) {
                // JSON array — import each card
                org.json.JSONArray arr = new org.json.JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    String cardJson = arr.getJSONObject(i).toString();
                    if (db.importFromJson(cardJson) >= 0) count++;
                }
            } else {
                // Single JSON object
                if (db.importFromJson(json) >= 0) count = 1;
            }
        } catch (Exception e) {
            FileLog.e(TAG, "importAll error: " + e.getMessage());
        }
        return count;
    }

    private void showExportAllDialog() {
        List<CardDatabase.CardEntry> cards = db.getRecentCards(100);
        if (cards.isEmpty()) {
            Toast.makeText(this, "No cards to export", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Export All Cards (" + cards.size() + ")");

        String[] options = {"Copy to clipboard", "Save to file"};
        builder.setItems(options, (dialog, which) -> {
            String json = buildExportJson(cards);
            if (json == null) {
                Toast.makeText(this, "Export error", Toast.LENGTH_SHORT).show();
                return;
            }
            if (which == 0) {
                // Copy to clipboard
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("cards_json", json);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, cards.size() + " card" + (cards.size() != 1 ? "s" : "") + " copied to clipboard", Toast.LENGTH_SHORT).show();
            } else {
                // Save to file
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
                    String filename = "LecteurOPUS_export_" + sdf.format(new Date()) + ".json";
                    File path = getFilesDir();
                    File jsonFile = new File(path, filename);
                    FileOutputStream fos = new FileOutputStream(jsonFile);
                    fos.write(json.getBytes());
                    fos.close();
                    Toast.makeText(this, "Saved " + cards.size() + " cards to " + filename, Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Save error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private String buildExportJson(List<CardDatabase.CardEntry> cards) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (CardDatabase.CardEntry entry : cards) {
                Card card = Card.fromRawSerialize(entry.rawXml);
                if (card != null) {
                    org.json.JSONObject obj = new org.json.JSONObject(card.rawJson());
                    // Include the custom display name and original scan time
                    String name = entry.getDisplayName();
                    if (name != null) obj.put("name", name);
                    obj.put("scanTime", entry.scanTime);
                    arr.put(obj);
                }
            }
            return arr.toString(2);
        } catch (Exception e) {
            FileLog.e(TAG, "buildExportJson error: " + e.getMessage());
            return null;
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
        popup.getMenu().add(0, 6, 0, "Compare");

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
                case 6: // Compare
                    showCompareDialog(entry);
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

    private CardDatabase.CardEntry compareTarget = null;

    private void showCompareDialog(CardDatabase.CardEntry entry) {
        if (compareTarget == null) {
            compareTarget = entry;
            Toast.makeText(this, "First card selected. Tap Compare on another card.", Toast.LENGTH_LONG).show();
        } else {
            // Check same card type
            if (!compareTarget.cardType.equals(entry.cardType)) {
                Toast.makeText(this, "Cannot compare different card types (" + compareTarget.cardType + " vs " + entry.cardType + ")", Toast.LENGTH_SHORT).show();
                compareTarget = null;
                return;
            }
            // Compare the two cards
            showDiff(compareTarget, entry);
            compareTarget = null;
        }
    }

    private void showDiff(CardDatabase.CardEntry a, CardDatabase.CardEntry b) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("=== CARD COMPARE ===\n\n");
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            sb.append("A: ").append(a.getDisplayName()).append(" (").append(sdf.format(new Date(a.scanTime))).append(")\n");
            sb.append("B: ").append(b.getDisplayName()).append(" (").append(sdf.format(new Date(b.scanTime))).append(")\n\n");

            // Compare raw XML pages
            String rawA = a.rawXml != null ? a.rawXml : "";
            String rawB = b.rawXml != null ? b.rawXml : "";

            if (rawA.contains("<Mifare>") && rawB.contains("<Mifare>")) {
                sb.append("--- MIFARE Page Diff ---\n");
                java.util.Map<Integer, String> pagesA = parseMifarePages(rawA);
                java.util.Map<Integer, String> pagesB = parseMifarePages(rawB);
                for (int i = 0; i < 16; i++) {
                    String hexA = pagesA.getOrDefault(i, "00000000");
                    String hexB = pagesB.getOrDefault(i, "00000000");
                    if (!hexA.equals(hexB)) {
                        sb.append(String.format("  Page %2d: %s → %s\n", i, hexA, hexB));
                    }
                }
            }

            if (rawA.contains("<Opus>") && rawB.contains("<Opus>")) {
                sb.append("\n--- OPUS Section Diff ---\n");
                String[] sections = {"ID", "Expiration", "Transit", "Subscription", "Ticket"};
                for (String sec : sections) {
                    String dataA = extractXmlSection(rawA, sec);
                    String dataB = extractXmlSection(rawB, sec);
                    if (!dataA.equals(dataB)) {
                        sb.append("  ").append(sec).append(": CHANGED\n");
                        sb.append("    A: ").append(dataA).append("\n");
                        sb.append("    B: ").append(dataB).append("\n");
                    }
                }
            }

            // Product code diff
            if (a.productCode != b.productCode) {
                sb.append("\nProduct code: 0x").append(String.format("%04X", a.productCode))
                  .append(" → 0x").append(String.format("%04X", b.productCode)).append("\n");
            }

            // Decoded field diff (OPUS only)
            if (rawA.contains("<Opus>") && rawB.contains("<Opus>")) {
                try {
                    Card cardA = Card.fromRawSerialize(rawA);
                    Card cardB = Card.fromRawSerialize(rawB);
                    if (cardA != null && cardB != null) {
                        boolean hasDiff = false;
                        sb.append("\n--- Decoded Fields ---\n");

                        // Expiration
                        if (cardA.getExpirationDate() != null || cardB.getExpirationDate() != null) {
                            String expA = cardA.getExpirationDate() != null ? Utils.DateToStringShort(cardA.getExpirationDate()) : "none";
                            String expB = cardB.getExpirationDate() != null ? Utils.DateToStringShort(cardB.getExpirationDate()) : "none";
                            if (!expA.equals(expB)) {
                                sb.append("  Expiration: ").append(expA).append(" → ").append(expB).append("\n");
                                hasDiff = true;
                            }
                        }

                        // Trips
                        java.util.ArrayList<Trip> tripsA = cardA.getTrip();
                        java.util.ArrayList<Trip> tripsB = cardB.getTrip();
                        if (tripsA.size() != tripsB.size()) {
                            sb.append("  Trips: ").append(tripsA.size()).append(" → ").append(tripsB.size()).append("\n");
                            hasDiff = true;
                        }
                        int tripCount = Math.min(tripsA.size(), tripsB.size());
                        for (int i = 0; i < tripCount; i++) {
                            Trip tA = tripsA.get(i);
                            Trip tB = tripsB.get(i);
                            String tripA = tripSummary(tA, i);
                            String tripB = tripSummary(tB, i);
                            if (!tripA.equals(tripB)) {
                                sb.append("  Trip ").append(i + 1).append(":\n");
                                sb.append("    A: ").append(tripA).append("\n");
                                sb.append("    B: ").append(tripB).append("\n");
                                hasDiff = true;
                            }
                        }

                        // Contracts
                        java.util.ArrayList<Contract> contA = cardA.getContract();
                        java.util.ArrayList<Contract> contB = cardB.getContract();
                        if (contA.size() != contB.size()) {
                            sb.append("  Contracts: ").append(contA.size()).append(" → ").append(contB.size()).append("\n");
                            hasDiff = true;
                        }
                        int contractCount = Math.min(contA.size(), contB.size());
                        for (int i = 0; i < contractCount; i++) {
                            Contract cA = contA.get(i);
                            Contract cB = contB.get(i);
                            String conA = contractSummary(cA);
                            String conB = contractSummary(cB);
                            boolean same = conA.equals(conB)
                                && cA.getContractGeneralBitmap() == cB.getContractGeneralBitmap()
                                && cA.getContractProvider() == cB.getContractProvider()
                                && cA.getContractTariff() == cB.getContractTariff()
                                && cA.getSaleAgent() == cB.getSaleAgent()
                                && cA.getUsedCounter() == cB.getUsedCounter()
                                && cA.getUseLimit() == cB.getUseLimit()
                                && cA.getScore() == cB.getScore()
                                && (cA.getSaleDate() == null ? cB.getSaleDate() == null : cA.getSaleDate().equals(cB.getSaleDate()));
                            if (!same) {
                                sb.append("  Contract ").append(i + 1).append(":\n");
                                sb.append("    A: ").append(conA).append("\n");
                                sb.append("       Bitmap=").append(cA.getContractGeneralBitmap())
                                  .append(" Provider=").append(cA.getContractProvider())
                                  .append(" Tariff=0x").append(String.format("%04X", cA.getContractTariff()))
                                  .append(" Agent=").append(cA.getSaleAgent())
                                  .append(" Used=").append(cA.getUsedCounter())
                                  .append(" Limit=").append(cA.getUseLimit())
                                  .append(" Score=").append(cA.getScore())
                                  .append(" Sale=").append(cA.getSaleDate() != null ? Utils.DateToStringShort(cA.getSaleDate()) : "?").append("\n");
                                sb.append("    B: ").append(conB).append("\n");
                                sb.append("       Bitmap=").append(cB.getContractGeneralBitmap())
                                  .append(" Provider=").append(cB.getContractProvider())
                                  .append(" Tariff=0x").append(String.format("%04X", cB.getContractTariff()))
                                  .append(" Agent=").append(cB.getSaleAgent())
                                  .append(" Used=").append(cB.getUsedCounter())
                                  .append(" Limit=").append(cB.getUseLimit())
                                  .append(" Score=").append(cB.getScore())
                                  .append(" Sale=").append(cB.getSaleDate() != null ? Utils.DateToStringShort(cB.getSaleDate()) : "?").append("\n");
                                hasDiff = true;
                            }
                        }

                        if (!hasDiff) {
                            sb.append("  (all decoded fields match)\n");
                        }
                    }
                } catch (Exception e) {
                    sb.append("  (decoded field comparison failed: ").append(e.getMessage()).append(")\n");
                }
            }

            final String diffText = sb.toString();
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Card Diff");

            // Scrollable text view so full diff is visible
            android.widget.ScrollView scroll = new android.widget.ScrollView(this);
            TextView textView = new TextView(this);
            textView.setText(diffText);
            textView.setTextSize(11);
            textView.setTypeface(android.graphics.Typeface.MONOSPACE);
            textView.setPadding(24, 16, 24, 16);
            scroll.addView(textView);
            builder.setView(scroll);

            builder.setPositiveButton("OK", null);
            builder.setNegativeButton("Copy", (dialog, which) -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("card_diff", diffText);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "Diff copied to clipboard", Toast.LENGTH_SHORT).show();
            });
            builder.show();

        } catch (Exception e) {
            Toast.makeText(this, "Compare error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String tripSummary(Trip t, int index) {
        StringBuilder s = new StringBuilder();
        if (t.getDateTime() != null) {
            s.append(Utils.DateToStringShort(t.getDateTime())).append(" ")
             .append(Utils.TimeToString(t.getDateTime())).append(" · ");
        }
        s.append(t.getBusName().isEmpty() ? "bus?" : t.getBusName());
        String op = t.getOperatorName();
        if (!op.isEmpty()) s.append(" (").append(op).append(")");
        if (t.getTransfer()) s.append(" [transfer]");
        return s.toString();
    }

    private String contractSummary(Contract c) {
        StringBuilder s = new StringBuilder();
        s.append(c.getProductName());
        if (c.isSubscription()) {
            if (c.getValidityDate() != null) {
                s.append(" until ").append(Utils.DateToStringShort(c.getValidityDate()));
            }
        } else {
            s.append(" — ").append(c.getNbTicket()).append(" trips");
        }
        s.append(c.isValid() ? " [valid]" : " [expired]");
        return s.toString();
    }

    private java.util.Map<Integer, String> parseMifarePages(String xml) {
        java.util.Map<Integer, String> map = new java.util.HashMap<>();
        try {
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(xml.getBytes("UTF-8"));
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document doc = builder.parse(bais);
            org.w3c.dom.NodeList pages = doc.getElementsByTagName("List");
            for (int i = 0; i < pages.getLength(); i++) {
                String idxStr = pages.item(i).getAttributes().getNamedItem("index").getNodeValue();
                int idx = Integer.parseInt(idxStr);
                map.put(idx, pages.item(i).getTextContent().trim());
            }
        } catch (Exception ignored) {}
        return map;
    }

    private String extractXmlSection(String xml, String tag) {
        try {
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(xml.getBytes("UTF-8"));
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document doc = builder.parse(bais);
            org.w3c.dom.NodeList nodes = doc.getElementsByTagName(tag);
            if (nodes.getLength() > 0) {
                return nodes.item(0).getTextContent().trim();
            }
        } catch (Exception ignored) {}
        return "";
    }
}
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

public class CardHistoryActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {

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
        scanning = true;
        NfcAdapter nfc = NfcAdapter.getDefaultAdapter(this);
        if (nfc == null) {
            Toast.makeText(this, "NFC not available", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!nfc.isEnabled()) {
            Toast.makeText(this, "Please enable NFC", Toast.LENGTH_SHORT).show();
            return;
        }
        nfc.enableReaderMode(this, this,
                NfcAdapter.FLAG_READER_NFC_A |
                NfcAdapter.FLAG_READER_NFC_B |
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK |
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, null);

        // Show a dialog instead of toast
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Tap a card");
        builder.setMessage("Hold your card against the back of the phone to scan it.");
        builder.setCancelable(true);
        builder.setOnDismissListener(d -> {
            if (scanning) {
                scanning = false;
                try { NfcAdapter.getDefaultAdapter(this).disableReaderMode(this); } catch (Exception ignored) {}
            }
        });
        scanDialog = builder.show();
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
                Toast.makeText(this, "Cannot open: invalid card data", Toast.LENGTH_SHORT).show();
                return;
            }

            Intent intent = new Intent(this, CardActivity.class);
            intent.putExtra("Card", card);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        if (!scanning) return;

        // Dismiss dialog immediately
        if (scanDialog != null) scanDialog.dismiss();
        scanning = false;
        try { NfcAdapter.getDefaultAdapter(this).disableReaderMode(this); } catch (Exception ignored) {}

        try {
            String techList = tag.toString();
            if (techList.contains(MifareUltralight.class.getName()) ||
                techList.contains(NfcA.class.getName())) {
                // MIFARE Ultralight (temp ticket)
                MifareUltralight mifare = MifareUltralight.get(tag);
                if (mifare != null) {
                    mifare.connect();
                    byte[][] cardData = new byte[][]{
                        mifare.readPages(0), mifare.readPages(4),
                        mifare.readPages(8), mifare.readPages(12) };
                    mifare.close();

                    Mifare mainCard = new Mifare(cardData);
                    Card card = mainCard.getCard();
                    db.saveCard(card);
                    runOnUiThread(() -> {
                        refreshList();
                        openCardFromCard(card);
                    });
                    return;
                }
            }

            if (techList.contains(IsoDep.class.getName()) ||
                techList.contains(NfcB.class.getName())) {
                // OPUS (Calypso)
                IsoDep opusCard = IsoDep.get(tag);
                if (opusCard != null) {
                    opusCard.connect();
                    Card card = readOpusCard(opusCard);
                    opusCard.close();
                    if (card != null) {
                        db.saveCard(card);
                        runOnUiThread(() -> {
                            refreshList();
                            openCardFromCard(card);
                        });
                    }
                    return;
                }
            }

            runOnUiThread(() -> Toast.makeText(this, "Unknown card type", Toast.LENGTH_SHORT).show());

        } catch (Exception e) {
            Log.e(TAG, "Error reading card: " + e.getMessage());
            runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    private Card readOpusCard(IsoDep opusCard) {
        byte[][] dataTransit = new byte[3][];
        byte[][] dataSubscription = new byte[4][];
        byte[][] dataTicket = new byte[4][];
        byte[] dataID = new byte[0];
        byte[] dataExp = new byte[0];

        try {
            // Try Montreal 1TIC.ICA SELECT
            try {
                byte[] r = opusCard.transceive(new byte[]{
                    (byte)0x00, (byte)0xA4, (byte)0x04, (byte)0x00,
                    (byte)0x08, 0x31, 0x54, 0x49, 0x43, 0x2E, 0x49, 0x43, 0x41});
                if (r.length >= 2 && r[r.length-2] == (byte)0x90 && r[r.length-1] == 0x00) {
                    opusCard.transceive(new byte[]{0x00, (byte)0xC0, 0x00, 0x00, 0x00});
                }
            } catch (Exception e) {
                // Fallback to Calypso standard SELECT
                try {
                    opusCard.transceive(Utils.HexStringToByteArray("00A404000200"));
                    opusCard.transceive(Utils.HexStringToByteArray("00A4040002000219"));
                } catch (Exception e2) {
                    opusCard.transceive(Utils.HexStringToByteArray("94A4000002000219"));
                }
            }

            // ICC
            try {
                opusCard.transceive(Utils.HexStringToByteArray("94A4000002000219"));
                dataID = opusCard.transceive(Utils.HexStringToByteArray("94B201041D"));
            } catch (Exception e) {
                Log.e(TAG, "ICC read failed: " + e.getMessage());
                dataID = Utils.HexStringToByteArray("0000000000000000000000000000000000000000000000000000000000000000");
            }

            // Environment
            try {
                opusCard.transceive(Utils.HexStringToByteArray("94A40800042001"));
                dataExp = opusCard.transceive(Utils.HexStringToByteArray("94B2010400"));
            } catch (Exception e) {
                Log.e(TAG, "Env read failed: " + e.getMessage());
                dataExp = Utils.HexStringToByteArray("0000000000000000000000000000000000000000000000000000000000000000");
            }

            // Transit
            try {
                opusCard.transceive(Utils.HexStringToByteArray("94A40800042010"));
                for (int i = 0; i < 3; i++) {
                    try {
                        dataTransit[i] = opusCard.transceive(Utils.HexStringToByteArray("94B20" + (i+1) + "0400"));
                    } catch (Exception e) {
                        dataTransit[i] = Utils.HexStringToByteArray("000000000000000000000000000000000000");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Transit read failed: " + e.getMessage());
            }

            // Subscription
            try {
                opusCard.transceive(Utils.HexStringToByteArray("94A40800042020"));
                for (int i = 0; i < 4; i++) {
                    try {
                        dataSubscription[i] = opusCard.transceive(Utils.HexStringToByteArray("94B20" + (i+1) + "0400"));
                    } catch (Exception e) {
                        dataSubscription[i] = Utils.HexStringToByteArray("000000000000000000000000000000000000");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Subscription read failed: " + e.getMessage());
            }

            // Tickets
            String[] ticketFiles = {"202A", "202B", "202C", "202D"};
            for (int i = 0; i < 4; i++) {
                try {
                    opusCard.transceive(Utils.HexStringToByteArray("94A4080004" + ticketFiles[i]));
                    dataTicket[i] = opusCard.transceive(Utils.HexStringToByteArray("94B2010400"));
                } catch (Exception e) {
                    dataTicket[i] = Utils.HexStringToByteArray("000000000000000000000000000000000000");
                }
            }

            Opus opus = new Opus(dataID, dataExp, dataTransit, dataSubscription, dataTicket);
            return opus.getCard();

        } catch (Exception e) {
            Log.e(TAG, "OPUS read failed: " + e.getMessage());
            return null;
        }
    }

    private void openCardFromCard(Card card) {
        Intent intent = new Intent(this, CardActivity.class);
        intent.putExtra("Card", card);
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
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
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
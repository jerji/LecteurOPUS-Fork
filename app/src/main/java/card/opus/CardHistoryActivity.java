package card.opus;

import android.app.Activity;
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
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_card_history);

        db = CardDatabase.getInstance(this);
        cardList = findViewById(R.id.cardList);
        emptyText = findViewById(R.id.emptyText);
        cardCount = findViewById(R.id.cardCount);
        Button scanButton = findViewById(R.id.scanButton);

        scanButton.setOnClickListener(v -> startScan());

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
        Toast.makeText(this, "Tap a card to scan", Toast.LENGTH_SHORT).show();
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
        } finally {
            scanning = false;
            try {
                NfcAdapter.getDefaultAdapter(this).disableReaderMode(this);
            } catch (Exception ignored) {}
        }
    }

    private Card readOpusCard(IsoDep opusCard) throws IOException {
        byte[][] dataTransit = new byte[3][];
        byte[][] dataSubscription = new byte[4][];
        byte[][] dataTicket = new byte[4][];

        // Try Montreal 1TIC.ICA first
        try {
            byte[] r = opusCard.transceive(new byte[]{
                (byte)0x00, (byte)0xA4, (byte)0x04, (byte)0x00,
                (byte)0x08, 0x31, 0x54, 0x49, 0x43, 0x2E, 0x49, 0x43, 0x41});
            if (r.length >= 2 && r[r.length-2] == (byte)0x90 && r[r.length-1] == 0x00) {
                opusCard.transceive(new byte[]{0x00, (byte)0xC0, 0x00, 0x00, 0x00});
            }
        } catch (Exception e) {
            opusCard.transceive(Utils.HexStringToByteArray("94A4000002000219"));
        }

        // ICC
        opusCard.transceive(Utils.HexStringToByteArray("94A4000002000219"));
        byte[] dataID = opusCard.transceive(Utils.HexStringToByteArray("94B201041D"));

        // Environment
        opusCard.transceive(Utils.HexStringToByteArray("94A40800042001"));
        byte[] dataExp = opusCard.transceive(Utils.HexStringToByteArray("94B2010400"));

        // Transit
        opusCard.transceive(Utils.HexStringToByteArray("94A40800042010"));
        for (int i = 0; i < 3; i++) {
            dataTransit[i] = opusCard.transceive(Utils.HexStringToByteArray("94B20" + (i+1) + "0400"));
        }

        // Subscription
        opusCard.transceive(Utils.HexStringToByteArray("94A40800042020"));
        for (int i = 0; i < 4; i++) {
            dataSubscription[i] = opusCard.transceive(Utils.HexStringToByteArray("94B20" + (i+1) + "0400"));
        }

        // Tickets
        String[] ticketFiles = {"202A", "202B", "202C", "202D"};
        for (int i = 0; i < 4; i++) {
            opusCard.transceive(Utils.HexStringToByteArray("94A4080004" + ticketFiles[i]));
            dataTicket[i] = opusCard.transceive(Utils.HexStringToByteArray("94B2010400"));
        }

        Opus opus = new Opus(dataID, dataExp, dataTransit, dataSubscription, dataTicket);
        return opus.getCard();
    }

    private void openCardFromCard(Card card) {
        Intent intent = new Intent(this, CardActivity.class);
        intent.putExtra("Card", card);
        startActivity(intent);
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

            // Set icon based on card type
            if ("OPUS".equals(entry.cardType)) {
                icon.setImageResource(android.R.drawable.ic_menu_compass);
            } else {
                icon.setImageResource(android.R.drawable.ic_menu_edit);
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

            return v;
        }
    }
}
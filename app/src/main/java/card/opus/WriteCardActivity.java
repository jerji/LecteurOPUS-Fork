package card.opus;

import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareUltralight;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;

import androidx.appcompat.app.AppCompatActivity;

public class WriteCardActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {

    private EditText productCodeInput;
    private EditText operatorInput;
    private EditText ticketsInput;
    private CheckBox subscriptionCheck;
    private boolean waitingForTag = false;
    private byte[][] pages = new byte[16][4];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Init all pages to zero
        for (int i = 0; i < 16; i++) Arrays.fill(pages[i], (byte) 0);

        buildUi();

        // Pre-fill from card if available
        Card card = (Card) getIntent().getSerializableExtra("Card");
        if (card != null && card.getContract() != null && card.getContract().size() > 0) {
            Contract c = card.getContract().get(0);
            productCodeInput.setText(String.format("%04X", c.getProductCode()));
            operatorInput.setText(String.valueOf(c.getOperatorId()));
            ticketsInput.setText(String.valueOf(c.getNbTicket()));
            subscriptionCheck.setChecked(c.isSubscription());
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 16);

        TextView title = new TextView(this);
        title.setText("Write Card");
        title.setTextSize(24);
        title.setPadding(0, 0, 0, 20);
        root.addView(title);

        // Product Code
        root.addView(makeLabel("Product Code (hex, e.g. 30DF):"));
        productCodeInput = new EditText(this);
        productCodeInput.setSingleLine(true);
        productCodeInput.setText("0000");
        root.addView(productCodeInput);

        // Operator ID
        root.addView(makeLabel("Operator ID:"));
        operatorInput = new EditText(this);
        operatorInput.setSingleLine(true);
        operatorInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        operatorInput.setText("0");
        root.addView(operatorInput);

        // Tickets Remaining
        root.addView(makeLabel("Tickets Remaining (0-255):"));
        ticketsInput = new EditText(this);
        ticketsInput.setSingleLine(true);
        ticketsInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        ticketsInput.setText("0");
        root.addView(ticketsInput);

        // Subscription
        subscriptionCheck = new CheckBox(this);
        subscriptionCheck.setText("Subscription card");
        root.addView(subscriptionCheck);

        // Info
        TextView info = new TextView(this);
        info.setText("\nValues will be encoded into MIFARE Ultralight pages 3-15 and written to the tag.");
        info.setPadding(0, 20, 0, 10);
        info.setTextSize(12);
        root.addView(info);

        // Write button
        Button writeBtn = new Button(this);
        writeBtn.setText("Encode & Write to Tag");
        writeBtn.setOnClickListener(v -> {
            if (!encodePages()) return;
            waitingForTag = true;
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
            if (adapter != null) {
                adapter.enableReaderMode(this, this,
                        NfcAdapter.FLAG_READER_NFC_A |
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null);
                Toast.makeText(this, "Tap a MIFARE Ultralight tag", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "NFC not available", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(writeBtn);

        // Reset button
        Button resetBtn = new Button(this);
        resetBtn.setText("Clear fields");
        resetBtn.setOnClickListener(v -> {
            productCodeInput.setText("0000");
            operatorInput.setText("0");
            ticketsInput.setText("0");
            subscriptionCheck.setChecked(false);
        });
        root.addView(resetBtn);

        setContentView(root);
    }

    private boolean encodePages() {
        // Reset data pages
        for (int i = 3; i < 16; i++) Arrays.fill(pages[i], (byte) 0);

        try {
            // Product code -> page 5 bytes 0-1
            String pcStr = productCodeInput.getText().toString().trim();
            if (pcStr.length() != 4) {
                Toast.makeText(this, "Product code must be 4 hex chars", Toast.LENGTH_SHORT).show();
                return false;
            }
            int productCode = Integer.parseInt(pcStr, 16);
            pages[5][0] = (byte) ((productCode >> 8) & 0xFF);
            pages[5][1] = (byte) (productCode & 0xFF);

            // Operator ID -> page 4 byte 0
            int operatorId = Integer.parseInt(operatorInput.getText().toString().trim());
            if (operatorId < 0 || operatorId > 255) {
                Toast.makeText(this, "Operator ID must be 0-255", Toast.LENGTH_SHORT).show();
                return false;
            }
            pages[4][0] = (byte) operatorId;

            // Tickets -> page 3 byte 0
            int tickets = Integer.parseInt(ticketsInput.getText().toString().trim());
            if (tickets < 0 || tickets > 255) {
                Toast.makeText(this, "Tickets must be 0-255", Toast.LENGTH_SHORT).show();
                return false;
            }
            pages[3][0] = (byte) tickets;

            // Subscription flag -> page 4 bit 8 + page 10 non-zero
            if (subscriptionCheck.isChecked()) {
                pages[4][1] = (byte) (pages[4][1] | 0x01);
                pages[10][0] = 0x01;
            }

            return true;

        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid number format", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private TextView makeLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setPadding(0, 8, 0, 4);
        tv.setTextSize(14);
        return tv;
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        if (!waitingForTag) return;

        MifareUltralight mifare = MifareUltralight.get(tag);
        if (mifare == null) {
            runOnUiThread(() -> Toast.makeText(this, "Not a MIFARE Ultralight tag", Toast.LENGTH_SHORT).show());
            return;
        }

        try {
            mifare.connect();

            // Read lock bytes from page 2
            byte[] p0to3 = mifare.readPages(0);
            int lockByte0 = p0to3[10] & 0xFF;
            boolean[] locked = new boolean[16];
            locked[0] = true;
            locked[1] = true;
            locked[2] = true;
            for (int b = 0; b < 8; b++) {
                if ((lockByte0 >> b & 1) == 1) {
                    int p = 3 + (b * 2);
                    if (p < 16) locked[p] = true;
                    if (p + 1 < 16) locked[p + 1] = true;
                }
            }

            // Write data to unlocked pages
            int written = 0;
            int skipped = 0;
            for (int i = 3; i < 16; i++) {
                if (locked[i]) {
                    skipped++;
                    continue;
                }
                mifare.writePage(i, pages[i]);
                written++;
            }

            mifare.close();

            final int w = written;
            final int s = skipped;
            runOnUiThread(() -> {
                String msg = "Written " + w + " pages";
                if (s > 0) msg += ", " + s + " locked skipped";
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            });

        } catch (Exception e) {
            final String err = e.getMessage();
            runOnUiThread(() -> Toast.makeText(this,
                    "Write failed: " + (err != null ? err : "unknown error"), Toast.LENGTH_LONG).show());
        } finally {
            waitingForTag = false;
            try {
                NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
                if (adapter != null) adapter.disableReaderMode(this);
            } catch (Exception ignored) {}
        }
    }
}
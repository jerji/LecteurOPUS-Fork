package card.opus;

import android.app.AlertDialog;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareUltralight;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import androidx.appcompat.app.AppCompatActivity;

public class WriteCardActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {

    // Spirtech FixedArea fields
    private EditText productCodeInput;
    private EditText operatorInput;
    private EditText ticketsInput;
    private CheckBox subscriptionCheck;
    private EditText maskInput;
    private EditText networkInput;
    private EditText exploitInput;
    private EditText ddvInput;
    private EditText dluInput;
    private EditText certInput;

    private boolean waitingForTag = false;
    private byte[][] pages = new byte[16][4];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        for (int i = 0; i < 16; i++) Arrays.fill(pages[i], (byte) 0);
        buildUi();
        prefillFromCard();
    }

    private void prefillFromCard() {
        Card card = (Card) getIntent().getSerializableExtra("Card");
        if (card == null || card.getContract() == null || card.getContract().size() == 0) return;

        Contract c = card.getContract().get(0);
        productCodeInput.setText(String.format("%04X", c.getProductCode()));
        operatorInput.setText(String.valueOf(c.getOperatorId()));
        ticketsInput.setText(String.valueOf(c.getNbTicket()));
        subscriptionCheck.setChecked(c.isSubscription());

        // Also try to read the raw serialized XML for FixedArea fields
        String raw = card.getRawSerialize();
        if (raw != null && raw.contains("<Mifare>")) {
            try {
                java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(raw.getBytes("UTF-8"));
                javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
                org.w3c.dom.Document doc = builder.parse(bais);
                org.w3c.dom.NodeList list = doc.getElementsByTagName("List");
                byte[] fixedArea = new byte[12];
                for (int i = 0; i < list.getLength(); i++) {
                    String idxStr = list.item(i).getAttributes().getNamedItem("index").getNodeValue();
                    int idx = Integer.parseInt(idxStr);
                    if (idx >= 5 && idx <= 7) {
                        String hex = list.item(i).getTextContent().trim();
                        byte[] pageData = hexStringToBytes(hex);
                        System.arraycopy(pageData, 0, fixedArea, (idx - 5) * 4, 4);
                    }
                }
                decodeFixedArea(fixedArea);
            } catch (Exception ignored) {}
        }
    }

    private void decodeFixedArea(byte[] data12) {
        int offset = 0;
        // Field 1: NumeroMasqueCodage (4 bits)
        int mask = bytesToInt(data12, offset, 4); offset += 4;
        maskInput.setText(String.format("%X", mask));

        // Field 2: NumeroProduit (14 bits) - skip, already set from productCodeInput
        offset += 14;

        // Field 3: ReseauTransportProduit (6 bits)
        int network = bytesToInt(data12, offset, 6); offset += 6;
        networkInput.setText(String.valueOf(network));

        // Field 4: BitTest (1 bit) - skip
        offset += 1;

        // Field 5: NumeroExploitantVendeur (7 bits)
        int exploit = bytesToInt(data12, offset, 7); offset += 7;
        exploitInput.setText(String.valueOf(exploit));

        // Field 6: DDVContract (14 bits)
        int ddv = bytesToInt(data12, offset, 14); offset += 14;
        if (ddv > 0) ddvInput.setText("" + ddv);

        // Field 7: DLUContract (14 bits)
        int dlu = bytesToInt(data12, offset, 14); offset += 14;
        if (dlu > 0) dluInput.setText("" + dlu);

        // Field 8: NonUtilise (4 bits) - skip
        offset += 4;

        // Field 9: Certificat (32 bits)
        int cert = bytesToInt(data12, offset, 32);
        certInput.setText(String.valueOf(cert));
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 16);

        TextView title = new TextView(this);
        title.setText("Write MIFARE Card");
        title.setTextSize(24);
        title.setPadding(0, 0, 0, 20);
        root.addView(title);

        // === Basic MIFARE fields ===
        root.addView(sectionLabel("MIFARE Page Data"));
        addField(root, "Product Code (hex, e.g. 30DF):",
                productCodeInput = hexField("0000"));

        addField(root, "Operator ID (0-255):",
                operatorInput = numField("0"));

        addField(root, "Tickets Remaining (0-255):",
                ticketsInput = numField("0"));

        subscriptionCheck = new CheckBox(this);
        subscriptionCheck.setText("Subscription card");
        root.addView(subscriptionCheck);

        // === Spirtech FixedArea fields ===
        root.addView(sectionLabel("Spirtech FixedArea (pages 5-7)"));

        addField(root, "MasqueCodage (hex, 0-F):",
                maskInput = hexFieldShort("3"));

        addField(root, "ReseauTransportProduit (0-63):",
                networkInput = numField("1"));

        addField(root, "NumeroExploitantVendeur (0-127):",
                exploitInput = numField("0"));

        addField(root, "DDVContract (days since 1997-01-01):",
                ddvInput = numField("0"));

        addField(root, "DLUContract (days since 1997-01-01):",
                dluInput = numField("0"));

        addField(root, "Certificat (32-bit):",
                certInput = numField("0"));

        // Info
        TextView info = new TextView(this);
        info.setText("\nValues are encoded into MIFARE Ultralight pages 3-15 per the Spirtech CCM bit layout.\nTap a blank tag to write.");
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

                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("Tap a card");
                builder.setMessage("Hold the card you want to write to against the back of the phone.");
                builder.setCancelable(true);
                builder.setOnDismissListener(d -> {
                    if (waitingForTag) {
                        waitingForTag = false;
                        try { adapter.disableReaderMode(this); } catch (Exception ignored) {}
                    }
                });
                builder.show();
            } else {
                Toast.makeText(this, "NFC not available", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(writeBtn);

        // Reset button
        Button resetBtn = new Button(this);
        resetBtn.setText("Clear all fields");
        resetBtn.setOnClickListener(v -> {
            productCodeInput.setText("0000");
            operatorInput.setText("0");
            ticketsInput.setText("0");
            subscriptionCheck.setChecked(false);
            maskInput.setText("3");
            networkInput.setText("1");
            exploitInput.setText("0");
            ddvInput.setText("0");
            dluInput.setText("0");
            certInput.setText("0");
        });
        root.addView(resetBtn);

        scroll.addView(root);
        setContentView(scroll);
    }

    // ---- Layout helpers ----
    private void addField(LinearLayout root, String label, EditText input) {
        root.addView(makeLabel(label));
        root.addView(input);
    }

    private EditText hexField(String def) {
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(def);
        return et;
    }

    private EditText hexFieldShort(String def) {
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(def);
        return et;
    }

    private EditText numField(String def) {
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setText(def);
        return et;
    }

    private TextView makeLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setPadding(0, 8, 0, 4);
        tv.setTextSize(14);
        return tv;
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setPadding(0, 16, 0, 8);
        return tv;
    }

    // ---- Spirtech bit encoding ----
    private boolean encodePages() {
        for (int i = 3; i < 16; i++) Arrays.fill(pages[i], (byte) 0);

        try {
            // Page 3: Ticket bitmap
            int tickets = Integer.parseInt(ticketsInput.getText().toString().trim());
            if (tickets < 0 || tickets > 255) {
                Toast.makeText(this, "Tickets must be 0-255", Toast.LENGTH_SHORT).show();
                return false;
            }
            // For a new card: page 3 all zeros, ticket count in page 10
            // For a used card: set ticket used bits
            if (subscriptionCheck.isChecked()) {
                pages[3][0] = (byte) 0x80; // "subscription" marker
                pages[4][1] = (byte) (pages[4][1] | 0x01);
                pages[10][0] = 0x01;
            } else if (tickets == 0) {
                pages[3][0] = (byte) 0xFF; // all tickets used
            } else {
                // Set the first N bits as used, rest as available
                for (int b = 31; b > 31 - tickets; b--) {
                    byte[] p3 = pages[3];
                    int bitPos = b;
                    p3[3 - bitPos/8] |= (byte) (1 << (bitPos % 8));
                }
            }

            // === Encode FixedArea (pages 5-7) using Spirtech bit layout ===
            byte[] fixedArea = new byte[12];
            int offset = 0;

            // Field 1: NumeroMasqueCodage (4 bits)
            int mask = Integer.parseInt(maskInput.getText().toString().trim(), 16);
            encodeBits(fixedArea, offset, 4, mask); offset += 4;

            // Field 2: NumeroProduit (14 bits)
            String pcStr = productCodeInput.getText().toString().trim();
            if (pcStr.length() != 4) {
                Toast.makeText(this, "Product code must be 4 hex chars", Toast.LENGTH_SHORT).show();
                return false;
            }
            int productCode = Integer.parseInt(pcStr, 16);
            encodeBits(fixedArea, offset, 14, productCode); offset += 14;

            // Field 3: ReseauTransportProduit (6 bits)
            int network = Integer.parseInt(networkInput.getText().toString().trim());
            encodeBits(fixedArea, offset, 6, network); offset += 6;

            // Field 4: BitTest (1 bit) - always 0
            encodeBits(fixedArea, offset, 1, 0); offset += 1;

            // Field 5: NumeroExploitantVendeur (7 bits)
            int exploit = Integer.parseInt(exploitInput.getText().toString().trim());
            encodeBits(fixedArea, offset, 7, exploit); offset += 7;

            // Field 6: DDVContract (14 bits) - days since 1997-01-01
            String ddvStr = ddvInput.getText().toString().trim();
            int ddv = !ddvStr.isEmpty() ? Integer.parseInt(ddvStr) : 0;
            encodeBits(fixedArea, offset, 14, ddv); offset += 14;

            // Field 7: DLUContract (14 bits)
            String dluStr = dluInput.getText().toString().trim();
            int dlu = !dluStr.isEmpty() ? Integer.parseInt(dluStr) : 0;
            encodeBits(fixedArea, offset, 14, dlu); offset += 14;

            // Field 8: NonUtilise (4 bits) - always 0
            encodeBits(fixedArea, offset, 4, 0); offset += 4;

            // Field 9: Certificat (32 bits)
            String certStr = certInput.getText().toString().trim();
            int cert = !certStr.isEmpty() ? Integer.parseInt(certStr) : 0;
            encodeBits(fixedArea, offset, 32, cert);

            // Copy FixedArea into pages 5, 6, 7
            System.arraycopy(fixedArea, 0, pages[5], 0, 4);
            System.arraycopy(fixedArea, 4, pages[6], 0, 4);
            System.arraycopy(fixedArea, 8, pages[7], 0, 4);

            // Page 4: Operator ID (byte 0) + flags (byte 2-3)
            int operatorId = Integer.parseInt(operatorInput.getText().toString().trim());
            if (operatorId < 0 || operatorId > 255) {
                Toast.makeText(this, "Operator ID must be 0-255", Toast.LENGTH_SHORT).show();
                return false;
            }
            pages[4][0] = (byte) operatorId;

            return true;

        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid number format", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void encodeBits(byte[] data, int firstBit, int length, int value) {
        for (int i = 0; i < length; i++) {
            int bitPos = firstBit + i;
            int byteIdx = bitPos / 8;
            int bitIdx = bitPos % 8;
            if ((value >> i & 1) != 0) {
                data[byteIdx] |= (byte) (1 << bitIdx);
            } else {
                data[byteIdx] &= (byte) ~(1 << bitIdx);
            }
        }
    }

    private static int bytesToInt(byte[] data, int firstBit, int length) {
        // LSB-first matching Java bytesToInt in LecteurOPUS
        int result = 0;
        int currentByte = firstBit / 8;
        int firstBitInByte = firstBit % 8;
        int lastByte = (firstBit + length - 1) / 8;
        int lastBit = (firstBit + length - 1) % 8;
        while (currentByte < lastByte) {
            int offset = 8 * (lastByte - currentByte - 1) + lastBit + 1;
            int mask = 0xFF >> firstBitInByte;
            result += (data[currentByte] & mask) << offset;
            firstBitInByte = 0;
            currentByte++;
        }
        int mask = 0xFF >> firstBitInByte;
        if (lastBit < 7) {
            mask &= (0xFF00 >> (lastBit + 1)) & 0xFF;
        }
        result += (data[currentByte] & mask) >> (7 - lastBit);
        return result;
    }

    private static byte[] hexStringToBytes(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
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
package card.opus;

import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareUltralight;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import androidx.appcompat.app.AppCompatActivity;

public class WriteCardActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {

    private EditText[] pageInputs = new EditText[16];
    private boolean[] pageLocked = new boolean[16];
    private boolean waitingForTag = false;
    private String[] pageHex = new String[16];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize all pages with defaults
        for (int i = 0; i < 16; i++) pageHex[i] = "00000000";

        // Try to load page data from intent (clone from read card)
        Card card = (Card) getIntent().getSerializableExtra("Card");
        if (card != null && card.getRawSerialize() != null) {
            parsePagesFromXml(card.getRawSerialize());
        }

        buildUi();
    }

    private void parsePagesFromXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));

            // Check for Pages format
            NodeList pageNodes = doc.getElementsByTagName("List");
            for (int i = 0; i < pageNodes.getLength(); i++) {
                String indexStr = pageNodes.item(i).getAttributes().getNamedItem("index").getNodeValue();
                String hex = pageNodes.item(i).getTextContent().trim();
                int idx = Integer.parseInt(indexStr);
                if (idx >= 0 && idx < 16 && hex.length() == 8) {
                    pageHex[idx] = hex;
                }
            }

            // Also check Opus format (ID, Expiration, Transit, Subscription, Ticket)
            parseOpusField(doc, "ID", 0, 16);
            parseOpusField(doc, "Expiration", 4, 12);
        } catch (Exception e) {
            // If XML parsing fails, just use defaults
        }
    }

    private void parseOpusField(Document doc, String tagName, int startPage, int hexLen) {
        try {
            NodeList nodes = doc.getElementsByTagName(tagName);
            if (nodes.getLength() > 0) {
                String hex = nodes.item(0).getTextContent().trim();
                // First 16 chars = ID, rest = serialized hex (pairs of 8 chars per page)
                if (hex.length() >= hexLen) {
                    String dataHex = hex.substring(0, Math.min(hexLen, hex.length()));
                    int pages = dataHex.length() / 8;
                    for (int p = 0; p < pages && (startPage + p) < 16; p++) {
                        pageHex[startPage + p] = dataHex.substring(p * 8, (p + 1) * 8);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 16);

        // Header
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setPadding(0, 0, 0, 16);

        Button cloneBtn = new Button(this);
        cloneBtn.setText("Clone from read");
        cloneBtn.setGravity(Gravity.CENTER);
        cloneBtn.setOnClickListener(v -> {
            Card card = (Card) getIntent().getSerializableExtra("Card");
            if (card != null && card.getRawSerialize() != null) {
                parsePagesFromXml(card.getRawSerialize());
                for (int i = 0; i < 16; i++) {
                    pageInputs[i].setText(pageHex[i]);
                }
                Toast.makeText(this, "Loaded card data - you can edit pages below", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "No card data available", Toast.LENGTH_SHORT).show();
            }
        });
        headerRow.addView(cloneBtn);

        Button clearBtn = new Button(this);
        clearBtn.setText("Clear all");
        clearBtn.setOnClickListener(v -> {
            for (int i = 0; i < 16; i++) {
                pageHex[i] = "00000000";
                pageInputs[i].setText("00000000");
            }
        });
        headerRow.addView(clearBtn);

        root.addView(headerRow);

        // Instructions
        TextView help = new TextView(this);
        help.setText("Edit the hex data below, then tap a MIFARE Ultralight tag to write.\nLocked pages (0-2) are skipped.");
        help.setPadding(0, 0, 0, 12);
        help.setTextSize(12);
        root.addView(help);

        // Lock info
        final TextView lockInfo = new TextView(this);
        lockInfo.setText("Lock status unknown (detected at write time)");
        lockInfo.setPadding(0, 0, 0, 8);
        lockInfo.setTextSize(12);
        root.addView(lockInfo);

        // Page editor
        ScrollView scroll = new ScrollView(this);
        LinearLayout pageLayout = new LinearLayout(this);
        pageLayout.setOrientation(LinearLayout.VERTICAL);

        for (int i = 0; i < 16; i++) {
            final int pageIdx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 4, 0, 4);

            TextView label = new TextView(this);
            String suffix = "";
            if (i == 0 || i == 1) suffix = " (UID, locked)";
            else if (i == 2) suffix = " (OTP/Lock, locked)";
            label.setText(String.format("Page %2d%s:", i, suffix));
            label.setWidth(190);
            row.addView(label);

            final EditText input = new EditText(this);
            input.setWidth(200);
            input.setSingleLine(true);
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            input.setText(pageHex[i]);
            pageInputs[i] = input;
            row.addView(input);

            pageLayout.addView(row);
        }

        scroll.addView(pageLayout);
        root.addView(scroll);

        // Write button
        Button writeBtn = new Button(this);
        writeBtn.setText("Write to Tag");
        writeBtn.setOnClickListener(v -> {
            // Collect hex values from inputs
            for (int i = 0; i < 16; i++) {
                pageHex[i] = pageInputs[i].getText().toString().trim();
                if (pageHex[i].length() != 8) pageHex[i] = "00000000";
            }
            waitingForTag = true;
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
            if (adapter != null) {
                adapter.enableReaderMode(this, this,
                        NfcAdapter.FLAG_READER_NFC_A |
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null);
                Toast.makeText(this, "Tap a MIFARE Ultralight tag to write", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "NFC not available", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(writeBtn);

        setContentView(root);
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

            // Read pages 0-2 to determine lock state
            byte[] p0to3 = mifare.readPages(0);

            // Page 2 byte 2 = lock byte 0 (bits 0-7)
            int lockByte0 = p0to3[10] & 0xFF;
            // Page 2 byte 3 = lock byte 1 (additional lock bits)
            int lockByte1 = p0to3[11] & 0xFF;

            // Determine which pages are locked
            // Lock byte 0 bits 0-7: bit N locks pages (N*2+3) and (N*2+4)
            // Lock byte 1 bits 0-7: similar, higher page numbers
            pageLocked[0] = true; // UID, always hardware locked
            pageLocked[1] = true; // UID cont'd
            pageLocked[2] = true; // OTP + lock bytes

            for (int b = 0; b < 8; b++) {
                if ((lockByte0 >> b & 1) == 1) {
                    int p = 3 + (b * 2);
                    if (p < 16) pageLocked[p] = true;
                    if (p + 1 < 16) pageLocked[p + 1] = true;
                }
                if ((lockByte1 >> b & 1) == 1) {
                    int p = 3 + 16 + (b * 2);
                    if (p < 16) pageLocked[p] = true;
                    if (p + 1 < 16) pageLocked[p + 1] = true;
                }
            }

            // Write unlocked pages
            int written = 0;
            int skipped = 0;
            for (int i = 3; i < 16; i++) {
                if (pageLocked[i]) {
                    skipped++;
                    continue;
                }
                byte[] data = hexStringToBytes(pageHex[i]);
                mifare.writePage(i, data);
                written++;
            }

            mifare.close();

            final int w = written;
            final int s = skipped;
            final StringBuilder lockMsg = new StringBuilder();
            for (int i = 3; i < 16; i++) {
                if (pageLocked[i]) lockMsg.append(" p").append(i);
            }

            runOnUiThread(() -> {
                String msg = "Written " + w + " pages";
                if (s > 0) msg += ", skipped locked:" + lockMsg;
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

    private byte[] hexStringToBytes(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }
}
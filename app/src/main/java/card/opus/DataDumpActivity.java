package card.opus;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

import androidx.appcompat.app.AppCompatActivity;

public class DataDumpActivity extends AppCompatActivity {
    public static final String TAG = "DataDump";

    // Spirtech CCM FixedArea field definitions (alias_FixedPart)
    private static final int[] FIXED_SIZES = {4, 14, 6, 1, 7, 14, 14, 4, 32};
    private static final String[] FIXED_NAMES = {
        "NumeroMasqueCodage", "NumeroProduit", "ReseauTransportProduit",
        "BitTest", "NumeroExploitantVendeur", "DDVContract",
        "DLUContract", "NonUtilise", "Certificat"
    };
    private static final String[] FIXED_FORMATS = {
        "hex", "decimal", "bitmap", "bool", "hex", "date", "date", "hex", "decimal"
    };

    private static int bytesToInt(byte[] data, int firstBit, int length) {
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
            mask = mask & (0xFF00 >> (lastBit + 1)) & 0xFF;
        }
        result += (data[currentByte] & mask) >> (7 - lastBit);
        return result;
    }

    private static String decodeFixedField(String name, int val, String fmt) {
        switch (fmt) {
            case "hex":
                return "0x" + Integer.toHexString(val).toUpperCase();
            case "bool":
                return val != 0 ? "true" : "false";
            case "bitmap":
                return String.format("%06b", val) + " = " + val;
            case "date": {
                if (val <= 0) return "none";
                Calendar cal = Calendar.getInstance();
                cal.set(1997, Calendar.JANUARY, 1);
                cal.add(Calendar.DAY_OF_YEAR, val);
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                return sdf.format(cal.getTime());
            }
            default:
                return String.valueOf(val);
        }
    }

    private static String decodeFixedArea(byte[] pages5to7, int productCodeOverride) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== Spirtech FixedArea (pages 5-7) ===\n");
        int offset = 0;
        for (int i = 0; i < FIXED_NAMES.length; i++) {
            int val = bytesToInt(pages5to7, offset, FIXED_SIZES[i]);
            // Override NumeroProduit with known-proper value
            if (FIXED_NAMES[i].equals("NumeroProduit") && productCodeOverride != 0) {
                val = productCodeOverride;
            }
            String decoded = decodeFixedField(FIXED_NAMES[i], val, FIXED_FORMATS[i]);
            sb.append("  ").append(String.format("%-30s", FIXED_NAMES[i]))
              .append(": ").append(decoded).append("\n");
            offset += FIXED_SIZES[i];
        }
        // Also show auth signatures from pages 14-15 if available
        sb.append("\nAuth signatures:\n");
        sb.append("  Page 14 upper bytes: checked at write time\n");
        sb.append("  Page 15 upper bytes: checked at write time\n");
        return sb.toString();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_data_dump);
        Utils.applySystemBarInsets(this);

        Card card = (Card) getIntent().getSerializableExtra("Card");
        TextView dumpContent = findViewById(R.id.dumpContent);

        StringBuilder sb = new StringBuilder();
        sb.append("=== CARD DATA DUMP ===\n\n");

        // Card type
        sb.append("Card Type: ").append(card.getType().name()).append("\n");

        // ID / UID
        if (card.getID() != 0) sb.append("ID: ").append(card.getID()).append("\n");

        // Product info
        sb.append("Product Code: 0x").append(String.format("%04X", card.getProductCode())).append("\n");
        if (card.getContract() != null && card.getContract().size() > 0) {
            Contract c = card.getContract().get(0);
            sb.append("Product Name: ").append(c.getProductName()).append("\n");
            sb.append("Operator ID: ").append(c.getOperatorId()).append("\n");
            if (c.getOperatorName() != null && !c.getOperatorName().isEmpty())
                sb.append("Operator: ").append(c.getOperatorName()).append("\n");
            sb.append("Tickets Remaining: ").append(c.getNbTicket()).append("\n");
            sb.append("Subscription: ").append(c.isSubscription()).append("\n");
            sb.append("Valid: ").append(c.isValid()).append("\n");
        }

        // Expiration
        if (card.getExpirationDate() != null) {
            sb.append("\nExpiration: ").append(Utils.DateToStringNumber(card.getExpirationDate())).append("\n");
        }

        // Trips
        if (card.getTrip() != null && card.getTrip().size() > 0) {
            sb.append("\n=== Trips ===\n");
            for (int i = 0; i < card.getTrip().size(); i++) {
                card.opus.Trip t = card.getTrip().get(i);
                if (t.getDateTime() != null) {
                    sb.append("Trip ").append(i).append(": ")
                        .append(Utils.DateToStringNumber(t.getDateTime())).append(" ")
                        .append(Utils.TimeToString(t.getDateTime())).append("\n");
                    sb.append("  Bus: ").append(t.getBusId())
                        .append(" | Operator: ").append(t.getOperatorId())
                        .append(" | Transfer: ").append(t.getTransfer()).append("\n");
                    if (t.getBusName() != null && !t.getBusName().isEmpty())
                        sb.append("  Bus Name: ").append(t.getBusName()).append("\n");
                    if (t.getOperatorName() != null && !t.getOperatorName().isEmpty())
                        sb.append("  Operator Name: ").append(t.getOperatorName()).append("\n");
                }
            }
        }

        // Spirtech FixedArea decode for MIFARE cards
        if (card.getType() == Card.CardType.OCCASIONNELLE) {
            try {
                // Extract pages 5-7 from raw serialize XML
                String raw = card.getRawSerialize();
                if (raw != null && raw.contains("<Mifare>")) {
                    // Parse hex pages from XML to reconstruct pages 5-7
                    java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(raw.getBytes("UTF-8"));
                    javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                    javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
                    org.w3c.dom.Document doc = builder.parse(bais);
                    org.w3c.dom.NodeList pages = doc.getElementsByTagName("List");
                    byte[] pages5to7 = new byte[12];
                    for (int i = 0; i < pages.getLength(); i++) {
                        String idxStr = pages.item(i).getAttributes().getNamedItem("index").getNodeValue();
                        int idx = Integer.parseInt(idxStr);
                        if (idx >= 5 && idx <= 7) {
                            String hex = pages.item(i).getTextContent().trim();
                            byte[] pageData = hexStringToBytes(hex);
                            System.arraycopy(pageData, 0, pages5to7, (idx - 5) * 4, 4);
                        }
                    }
                    sb.append(decodeFixedArea(pages5to7, card.getProductCode()));
                }
            } catch (Exception e) {
                sb.append("\n(FixedArea decode unavailable)\n");
            }
        }

        // All contracts
        if (card.getContract() != null && card.getContract().size() > 0) {
            sb.append("\n=== Contracts ===\n");
            for (int i = 0; i < card.getContract().size(); i++) {
                Contract c = card.getContract().get(i);
                sb.append("Contract ").append(i).append(":\n");
                sb.append("  Product Code: 0x").append(String.format("%04X", c.getProductCode())).append("\n");
                sb.append("  Product: ").append(c.getProductName()).append("\n");
                sb.append("  Operator: ").append(c.getOperatorId()).append("\n");
                sb.append("  Tickets: ").append(c.getNbTicket()).append("\n");
                sb.append("  Subscription: ").append(c.isSubscription()).append("\n");
                sb.append("  Valid: ").append(c.isValid()).append("\n");
                if (c.getValidityDate() != null)
                    sb.append("  Valid Until: ").append(Utils.DateToStringNumber(c.getValidityDate())).append("\n");
            }
        }

        // Raw serialize
        sb.append("\n=== Raw Card Data ===\n");
        sb.append(card.getRawSerialize());

        dumpContent.setText(sb.toString());

        Button copyButton = findViewById(R.id.copyButton);
        copyButton.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("card_dump", sb.toString());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show();
        });

        Button jsonButton = findViewById(R.id.jsonButton);
        jsonButton.setOnClickListener(v -> {
            try {
                String json = card.rawJson();
                // Build filename: type_productCode_date
                String type = card.getType() == Card.CardType.OPUS ? "OPUS" : "MIFARE";
                String code = String.format("%04X", card.getProductCode());
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US);
                String dateStr = sdf.format(new java.util.Date());
                String filename = type + "_" + code + "_" + dateStr + ".json";

                java.io.File path = getFilesDir();
                java.io.File jsonFile = new java.io.File(path, filename);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(jsonFile);
                fos.write(json.getBytes());
                fos.close();

                // Also copy to clipboard
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("card_json", json);
                clipboard.setPrimaryClip(clip);

                Toast.makeText(this, "JSON copied & saved as " + filename, Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        Button writeButton = findViewById(R.id.writeButton);
        writeButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, WriteCardActivity.class);
            intent.putExtra("Card", card);
            startActivity(intent);
        });
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
}
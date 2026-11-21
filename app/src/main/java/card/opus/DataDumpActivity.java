package card.opus;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
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
            case "bitmap": {
                String bin = Integer.toBinaryString(val);
                while (bin.length() < 6) bin = "0" + bin;
                return bin + " = " + val;
            }
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
        return sb.toString();
    }

    // Parse auth signatures from raw XML (pages 14-15 bytes 2-3)
    private String decodeAuthSignatures(String rawXml) {
        if (rawXml == null || !rawXml.contains("<Mifare>")) return "";
        try {
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(rawXml.getBytes("UTF-8"));
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document doc = builder.parse(bais);
            org.w3c.dom.NodeList pages = doc.getElementsByTagName("List");
            String sig14 = null, sig15 = null;
            for (int i = 0; i < pages.getLength(); i++) {
                String idxStr = pages.item(i).getAttributes().getNamedItem("index").getNodeValue();
                int idx = Integer.parseInt(idxStr);
                String hex = pages.item(i).getTextContent().trim();
                if (idx == 14 && hex.length() >= 8) {
                    sig14 = hex.substring(4, 8);
                }
                if (idx == 15 && hex.length() >= 8) {
                    sig15 = hex.substring(4, 8);
                }
            }
            StringBuilder sb = new StringBuilder("\n=== Auth Signatures (pages 14-15) ===\n");
            if (sig14 != null) {
                sb.append("  Page 14 sig: 0x").append(sig14).append("\n");
            }
            if (sig15 != null) {
                sb.append("  Page 15 sig: 0x").append(sig15).append("\n");
            }
            sb.append("  (SAM AES-CMAC, verified by fare gate hardware)\n");
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
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

        // Expiration (file 0x2001)
        if (card.getExpirationDate() != null) {
            sb.append("\nExpiration (file 0x2001): ").append(Utils.DateToStringNumber(card.getExpirationDate())).append("\n");
        }

        // Trips (file 0x2010)
        if (card.getTrip() != null && card.getTrip().size() > 0) {
            sb.append("\n=== Trips (file 0x2010) ===\n");
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
                FileLog.e("DataDump", "FixedArea decode error: " + e.getMessage(), e);
                sb.append("\n(FixedArea decode unavailable)\n");
            }
        }

        // Auth signatures for MIFARE cards
        if (card.getType() == Card.CardType.OCCASIONNELLE) {
            String authSigs = decodeAuthSignatures(card.getRawSerialize());
            sb.append(authSigs);
        }

        // All contracts with full decode
        if (card.getContract() != null && card.getContract().size() > 0) {
            // File IDs for OPUS records
            String[] subFileIds = {"0x2020", "0x2020", "0x2020", "0x2020"};
            String[] ticketFileIds = {"0x202A", "0x202B", "0x202C", "0x202D"};
            String[] envFileIds = {"0x2001"};
            String[] transitFileIds = {"0x2010", "0x2010", "0x2010"};

            sb.append("\n=== Contracts ===\n");
            for (int i = 0; i < card.getContract().size(); i++) {
                Contract c = card.getContract().get(i);
                String fileId = (i < subFileIds.length) ? subFileIds[i] : "";
                String ticketFileId = (i < ticketFileIds.length) ? ticketFileIds[i] : "";

                sb.append("Contract ").append(i).append(" (Sub file ").append(fileId)
                  .append(", Ticket file ").append(ticketFileId).append("):\n");
                sb.append("  Product Code: 0x").append(String.format("%04X", c.getProductCode())).append("\n");
                sb.append("  Product: ").append(c.getProductName()).append("\n");
                sb.append("  Operator: ").append(c.getOperatorId()).append("\n");
                sb.append("  Tickets: ").append(c.getNbTicket()).append("\n");
                sb.append("  Subscription: ").append(c.isSubscription()).append("\n");
                sb.append("  Valid: ").append(c.isValid()).append("\n");
                if (c.getValidityDate() != null && c.getValidityDate().get(java.util.Calendar.YEAR) > 1997)
                    sb.append("  Valid Until: ").append(Utils.DateToStringNumber(c.getValidityDate())).append("\n");

                // Full Spirtech contract decode
                if (c.getContractGeneralBitmap() != 0) {
                    sb.append("  --- Spirtech Contract Fields ---\n");
                    sb.append("  GeneralBitmap: 0x").append(Integer.toHexString(c.getContractGeneralBitmap())).append("\n");
                    sb.append("  Provider: ").append(c.getContractProvider()).append("\n");
                    sb.append("  Tariff: 0x").append(String.format("%04X", c.getContractTariff())).append("\n");
                    sb.append("  SaleAgent: ").append(c.getSaleAgent()).append("\n");
                    sb.append("  UsedCounter: ").append(c.getUsedCounter()).append("\n");
                    sb.append("  UseLimit: ").append(c.getUseLimit()).append("\n");
                    sb.append("  Score: ").append(c.getScore()).append("\n");
                    if (c.getSaleDate() != null)
                        sb.append("  SaleDate: ").append(Utils.DateToStringNumber(c.getSaleDate())).append("\n");
                    sb.append("  Context: 0x").append(String.format("%02X", c.getContractContext())).append("\n");
                    if (c.getRawHex() != null) {
                        String raw = c.getRawHex();
                        sb.append("  Raw: ").append(raw.length() > 48 ? raw.substring(0, 48) + "..." : raw).append("\n");
                    }
                }
            }
        }

        // Extra data from full OPUS enumeration
        if (card.getExtraData() != null && !card.getExtraData().isEmpty()) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);

            // Show found files list first
            if (card.getExtraData().containsKey("_found_files")) {
                String files = new String(card.getExtraData().get("_found_files"));
                sb.append("\n=== Files Found on Card (").append(files.isEmpty() ? "0" : files.split(",").length).append(") ===\n");
                if (!files.isEmpty()) {
                    String[] parts = files.split(",");
                    for (String f : parts) sb.append("  ").append(f).append("\n");
                }
            }

            // Show directory info if available
            if (card.getExtraData().containsKey("_directory")) {
                byte[] dirData = card.getExtraData().get("_directory");
                sb.append("\n--- Directory (file 0x0000) ---\n");
                for (int i = 0; i < dirData.length; i += 16) {
                    StringBuilder hex = new StringBuilder();
                    StringBuilder ascii = new StringBuilder();
                    for (int j = 0; j < 16 && i + j < dirData.length; j++) {
                        int b = dirData[i + j] & 0xFF;
                        hex.append(String.format("%02X ", b));
                        ascii.append(b >= 32 && b < 127 ? (char)b : '.');
                    }
                    sb.append(String.format("  %04X: %-48s %s\n", i, hex, ascii));
                }
            }

            // Show individual file data
            sb.append("\n=== File Data ===\n");
            for (java.util.Map.Entry<String, byte[]> entry : card.getExtraData().entrySet()) {
                String key = entry.getKey();
                byte[] val = entry.getValue();
                if (key.startsWith("_")) continue; // skip metadata keys

                sb.append("  ").append(key).append(" (").append(val.length).append(" bytes): ");
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < Math.min(val.length, 32); i++) hex.append(String.format("%02X", val[i]));
                sb.append(hex);
                if (val.length > 32) sb.append("...");
                sb.append("\n");

                // Try to decode validity date from longer records
                if (val.length >= 26) {
                    try {
                        int days = Utils.bytesToInt(val, 24, 14);
                        if (days > 0) {
                            Calendar cal = Calendar.getInstance();
                            cal.set(1997, Calendar.JANUARY, 1);
                            cal.add(Calendar.DAY_OF_YEAR, days);
                            sb.append("    Possible validity: ").append(sdf.format(cal.getTime())).append("\n");
                        }
                    } catch (Exception ignored) {}
                }
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

        Button copyJsonButton = findViewById(R.id.copyJsonButton);
        copyJsonButton.setOnClickListener(v -> {
            try {
                String json = card.rawJson();
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("card_json", json);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "JSON copied to clipboard", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        Button saveJsonButton = findViewById(R.id.saveJsonButton);
        saveJsonButton.setOnClickListener(v -> {
            try {
                String json = card.rawJson();
                String type = card.getType() == Card.CardType.OPUS ? "OPUS" : "MIFARE";
                String code = String.format("%04X", card.getProductCode());
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
                String dateStr = sdf.format(new Date());
                String filename = type + "_" + code + "_" + dateStr + ".json";

                File path = getFilesDir();
                File jsonFile = new File(path, filename);
                FileOutputStream fos = new FileOutputStream(jsonFile);
                fos.write(json.getBytes());
                fos.close();

                Toast.makeText(this, "Saved: " + filename, Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        Button writeButton = findViewById(R.id.writeButton);
        if (card.getType() == Card.CardType.OPUS) {
            writeButton.setVisibility(View.GONE);
        } else {
            writeButton.setOnClickListener(v -> {
                Intent intent = new Intent(this, WriteCardActivity.class);
                intent.putExtra("Card", card);
                startActivity(intent);
            });
        }
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
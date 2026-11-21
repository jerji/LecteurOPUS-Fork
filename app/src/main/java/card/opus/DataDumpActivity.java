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

import androidx.appcompat.app.AppCompatActivity;

public class DataDumpActivity extends AppCompatActivity {
    public static final String TAG = "DataDump";

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
                java.io.File path = getFilesDir();
                java.io.File jsonFile = new java.io.File(path, "card_export.json");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(jsonFile);
                fos.write(json.getBytes());
                fos.close();
                Toast.makeText(this, "JSON saved to " + jsonFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
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
}
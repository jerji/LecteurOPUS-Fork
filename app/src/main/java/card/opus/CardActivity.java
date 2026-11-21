package card.opus;

import static card.opus.Utils.isInternetAvailable;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.Settings;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import android.widget.ScrollView;

public class CardActivity extends AppCompatActivity {
    public static final String TAG = "CardActivity";
    Card m_Card;
    int m_OperatorId;
    int m_BusId;
    String m_TimeHash;
    File m_PreviousAddTrip;
    List<ImageButton> m_AddButtons;
    private long entryId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_card);
        Utils.applySystemBarInsets(this);

        Intent intent = getIntent();
        m_Card = (Card)intent.getSerializableExtra("Card");

        // Set title from entry name or card type
        String entryName = intent.getStringExtra("entryName");
        if (entryName != null && !entryName.isEmpty()) {
            setTitle(entryName);
        } else if (m_Card != null) {
            switch (m_Card.getType()) {
                case OPUS:
                    setTitle("OPUS Card");
                    break;
                case OCCASIONNELLE:
                    String cn = m_Card.getContract().get(0).getProductName();
                    if (cn.equals("Unknown (code 0000)") && m_Card.getProductCode() != 0) {
                        cn = String.format("Unknown (code %04X)", m_Card.getProductCode());
                    }
                    setTitle(cn);
                    break;
                default:
                    setTitle("Card");
            }
        } else {
            setTitle("Card");
        }
        entryId = intent.getLongExtra("entryId", -1);

        LinearLayout LayoutTransit = (LinearLayout) findViewById(R.id.layoutTransit);
        LinearLayout LayoutSubscription = (LinearLayout) findViewById(R.id.layoutSubcription);
        LinearLayout LayoutTicket = (LinearLayout) findViewById(R.id.layoutTicket);
        LinearLayout layoutCardId = (LinearLayout) findViewById(R.id.layoutCardId);

        TextView textType = (TextView) this.findViewById(R.id.CARTE_TYPE);
        ImageView imageType = (ImageView) this.findViewById(R.id.imageType);
        TextView textTransit = (TextView) this.findViewById(R.id.textTransit);
        TextView textExpiration = (TextView) this.findViewById(R.id.textExpiration);
        TextView textID = (TextView) this.findViewById(R.id.cardID);
        ImageView imageLogo = (ImageView) this.findViewById(R.id.imageLogo);

        LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        // TypeCard
        switch (m_Card.getType()){
            case OCCASIONNELLE:
                textType.setText(R.string.card_occa);
                imageType.setVisibility(View.GONE);
                textExpiration.setVisibility(View.GONE);
                layoutCardId.setVisibility(View.GONE);
                m_Card.getContract().get(0).setLogoFromXml(this);
                int logoId = m_Card.getContract().get(0).getLogoId();
                if (logoId != 0) imageLogo.setImageResource(logoId);

                // Display product code and name
                if (m_Card.getProductCode() != 0) {
                    String productInfo = m_Card.getContract().get(0).getProductName();
                    textType.setText(getString(R.string.card_occa) + " - " + productInfo);
                }

                // Populate fare summary
                populateFareSummary();
                break;
                            case OPUS:
                                String ID = "" + m_Card.getID();
                                textID.setText("n° " + ID.substring(0,3) + " " + ID.substring(3,6) + " " + ID.substring(6));
                                textType.setText(R.string.card_opus);
                                imageType.setBackgroundResource(R.mipmap.opus);
                                textExpiration.setText(getString(R.string.expiration) + " "
                                        + Utils.DateToStringShort(m_Card.getExpirationDate()));

                                // Populate fare products summary
                                populateFareSummary();
                                break;
                            default:
                // do nothing
                break;
        }

        // Transit
        ArrayList<Trip> trip = m_Card.getTrip();
        if (trip.size() == 0){
            LayoutTransit.setVisibility(View.GONE);
        }
        else {
            if (trip.size() == 1){
                textTransit.setText(R.string.last_trip);
            }

            File path = this.getFilesDir();
            m_PreviousAddTrip = new File(path, "cardsAdd.txt");
            List<String> list = new ArrayList<>();
            try {
                BufferedReader br = new BufferedReader(new FileReader(m_PreviousAddTrip));
                String str;
                while((str = br.readLine()) != null){
                    list.add(str);
                }
            }
            catch (IOException e){
                Log.e(TAG, "File not found");
            }

            m_AddButtons = new ArrayList<>();
            for (int i = 0; i < trip.size(); i++) {
                trip.get(i).setBusFromXml(this); // Set bus name and operator
                View customTransit = inflater.inflate(R.layout.transit, null);
                TextView textDate = (TextView) customTransit.findViewById(R.id.textDate);
                TextView textTime = (TextView) customTransit.findViewById(R.id.textTime);
                TextView textBus = (TextView) customTransit.findViewById(R.id.textBus);
                ImageView imageBus = (ImageView) customTransit.findViewById(R.id.imageBus);
                TextView textTransfer = (TextView) customTransit.findViewById(R.id.textTransfer);
                ImageButton addButton = (ImageButton) customTransit.findViewById(R.id.addButton);
                m_AddButtons.add(addButton);

                Calendar tempo = trip.get(i).getDateTime();
                textTime.setText(Utils.TimeToString(tempo));
                textDate.setText(Utils.DateToString(tempo));

                // Transfer
                if(trip.get(i).getTransfer() != true){
                    textTransfer.setVisibility(View.GONE);
                }
                imageBus.setImageResource(trip.get(i).getLogoId());

                String name = trip.get(i).getBusName();
                if(name.equals(""))
                    name = getString(R.string.unknown);

                String tripId = trip.get(i).getTripId();
                if (!list.contains(tripId)) {
                    addButton.setTag(i);
                }
                else{
                    addButton.setVisibility(View.GONE);
                }
                textBus.setText(name);

                LayoutTransit.addView(customTransit);
            }
        }

        // ---- Data Dump Button (JSON actions are in the ⋮ menu) ----
        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setPadding(16, 16, 16, 16);

        Button dumpButton = new Button(this);
        dumpButton.setText("Show All Data");
        dumpButton.setOnClickListener(v -> {
            Intent dumpIntent = new Intent(this, DataDumpActivity.class);
            dumpIntent.putExtra("Card", m_Card);
            startActivity(dumpIntent);
        });

        buttonRow.addView(dumpButton);

        LinearLayout mainLayout = findViewById(R.id.layoutTransit);
        if (mainLayout != null && mainLayout.getParent() instanceof LinearLayout) {
            ((LinearLayout) mainLayout.getParent()).addView(buttonRow);
        }

        if (BuildConfig.DEBUG) {
            File path = this.getFilesDir();
            File cardRaw = new File(path, "card.xml");
            File expected = new File(path, "expected.xml");
            try {
                FileOutputStream streamRaw = new FileOutputStream(cardRaw);
                FileOutputStream streamExpected = new FileOutputStream(expected);
                streamRaw.write(m_Card.getRawSerialize().getBytes());
                streamRaw.close();
                streamExpected.write(m_Card.serialize().getBytes());
                streamExpected.close();
            }
            catch (IOException e){
                Log.e(TAG, "Error writing file");
            }
        }
    }

    protected String pluralForm(int n)
    {
        n = Math.abs(n) % 100;
        int n1 = n % 10;
        if (n > 10 && n < 20) return getString(R.string.ticket_remaining5);
        if (n1 > 1 && n1 < 5) return getString(R.string.ticket_remaining2);
        if (n1 == 1) return getString(R.string.ticket_remaining1);
        return getString(R.string.ticket_remaining5);
    }

    public void addUnknown(View v) {
        if(!isInternetAvailable()){
            Toast.makeText(this, R.string.connection_error, Toast.LENGTH_SHORT).show();
            return;
        }

        final int index =  Integer.valueOf(v.getTag().toString());
        m_OperatorId = m_Card.getTrip().get(index).getOperatorId();
        m_BusId = m_Card.getTrip().get(index).getBusId();
        final String operatorName = m_Card.getTrip().get(index).getOperatorName();
        final String busName = m_Card.getTrip().get(index).getBusName();
        m_TimeHash = m_Card.getTrip().get(index).getTripId();

        final AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        alertDialogBuilder.setTitle(R.string.help_us);

        alertDialogBuilder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        if(busName.equals("")){
            LayoutInflater layoutInflater = LayoutInflater.from(this);
            View promptView = layoutInflater.inflate(R.layout.input_dialog, null);
            alertDialogBuilder.setView(promptView);

            // Hide spinners, show freeform text fields
            promptView.findViewById(R.id.operatorInput).setVisibility(View.GONE);
            promptView.findViewById(R.id.operatorText).setVisibility(View.VISIBLE);
            promptView.findViewById(R.id.busLabel).setVisibility(View.VISIBLE);
            promptView.findViewById(R.id.busLayout).setVisibility(View.VISIBLE);
            promptView.findViewById(R.id.busInput).setVisibility(View.GONE);
            promptView.findViewById(R.id.busText).setVisibility(View.VISIBLE);

            final EditText operatorText = (EditText) promptView.findViewById(R.id.operatorText);
            final EditText busText = (EditText) promptView.findViewById(R.id.busText);
            operatorText.setHint("Operator (e.g. STM)");
            busText.setHint("Bus/route name or number");

            alertDialogBuilder.setPositiveButton(R.string.send, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String op = operatorText.getText().toString().trim();
                    String bu = busText.getText().toString().trim();
                    if(!bu.isEmpty() || !op.isEmpty()){
                        if(sendData(bu, op, true, true))
                            m_AddButtons.get(index).setVisibility(View.GONE);
                    }
                }
            });
            alertDialogBuilder.show();
        }
        else{ // Vote
            LayoutInflater layoutInflater = LayoutInflater.from(this);
            View promptView = layoutInflater.inflate(R.layout.vote_dialog, null);
            alertDialogBuilder.setView(promptView);

            TextView text = (TextView) promptView.findViewById(R.id.operatorBusText);
            text.setText(operatorName + " - " + busName);

            final AlertDialog alert = alertDialogBuilder.show();

            Button good = (Button) promptView.findViewById(R.id.button_good);
            good.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if(sendData(busName, operatorName, true, false))
                        m_AddButtons.get(index).setVisibility(View.GONE);
                    alert.cancel();
                }
            });

            Button bad = (Button) promptView.findViewById(R.id.button_bad);

            View.OnClickListener listener = new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if(sendData(busName, operatorName, false, false))
                        m_AddButtons.get(index).setVisibility(View.GONE);
                    alert.cancel();
                }
            };
            bad.setOnClickListener(listener);
        }
    }

    public boolean sendData(String busName, String operatorName, boolean add, boolean missing){
        if((operatorName.equals("") || busName.equals("")) && !missing){
            return false;
        }
        final String bn = busName;
        final String on = operatorName;
        final boolean isAdd = add;
        new Thread(() -> {
            try {
                String json;
                if (missing) {
                    json = "{\"bus_name\":\"" + bn.replace("\"", "\\\\\"") + "\",\"operator_name\":\"" + on.replace("\"", "\\\\\"") + "\"}";
                    java.net.URL url = new java.net.URL(ServerConfig.BASE_URL + "/api/v1/bus/submit");
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("X-API-Key", ServerConfig.API_KEY);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(10000);
                    conn.getOutputStream().write(json.getBytes("UTF-8"));
                    int code = conn.getResponseCode();
                    conn.disconnect();

                    // Write trip hash to prevent re-submitting same trip
                    if (code == 200 && m_TimeHash != null && !m_TimeHash.isEmpty()) {
                        try {
                            java.io.BufferedWriter bw = new java.io.BufferedWriter(new java.io.FileWriter(m_PreviousAddTrip, true));
                            bw.write(m_TimeHash);
                            bw.newLine();
                            bw.flush();
                            bw.close();
                        } catch (java.io.IOException e) {
                            Log.e(TAG, "Error writing file");
                        }
                    }

                    final int respCode = code;
                    runOnUiThread(() -> {
                        if (respCode == 200) Toast.makeText(this, "Submitted", Toast.LENGTH_SHORT).show();
                        else Toast.makeText(this, "Server error", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    json = "{\"bus_name\":\"" + bn.replace("\"", "\\\\\"") + "\",\"operator_id\":0,\"bus_id\":0,\"vote\":" + (isAdd ? 1 : -1) + "}";
                    java.net.URL url = new java.net.URL(ServerConfig.BASE_URL + "/api/v1/bus/vote");
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("X-API-Key", ServerConfig.API_KEY);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(10000);
                    conn.getOutputStream().write(json.getBytes("UTF-8"));
                    conn.getResponseCode();
                    conn.disconnect();

                    // Write trip hash to prevent re-submitting same trip
                    if (m_TimeHash != null && !m_TimeHash.isEmpty()) {
                        try {
                            java.io.BufferedWriter bw = new java.io.BufferedWriter(new java.io.FileWriter(m_PreviousAddTrip, true));
                            bw.write(m_TimeHash);
                            bw.newLine();
                            bw.flush();
                            bw.close();
                        } catch (java.io.IOException e) {
                            Log.e(TAG, "Error writing file");
                        }
                    }

                    if (!isAdd) {
                        runOnUiThread(() -> {
                            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
                            builder.setTitle("Correct this bus line");
                            builder.setMessage("Current: " + bn + " (" + on + ")\nWhat should it be?");

                            android.view.LayoutInflater inflater = LayoutInflater.from(this);
                            android.view.View promptView = inflater.inflate(R.layout.input_dialog, null);
                            builder.setView(promptView);

                            // Use freeform text fields instead of spinners
                            promptView.findViewById(R.id.operatorInput).setVisibility(android.view.View.GONE);
                            promptView.findViewById(R.id.operatorText).setVisibility(android.view.View.VISIBLE);
                            promptView.findViewById(R.id.busLabel).setVisibility(android.view.View.VISIBLE);
                            promptView.findViewById(R.id.busLayout).setVisibility(android.view.View.VISIBLE);
                            promptView.findViewById(R.id.busInput).setVisibility(android.view.View.GONE);
                            promptView.findViewById(R.id.busText).setVisibility(android.view.View.VISIBLE);

                            android.widget.EditText operatorText = (android.widget.EditText) promptView.findViewById(R.id.operatorText);
                            android.widget.EditText busText = (android.widget.EditText) promptView.findViewById(R.id.busText);
                            operatorText.setHint("Operator (e.g. STM)");
                            busText.setHint("Bus/route name or number");

                            builder.setPositiveButton("Submit correction", (dialog, which) -> {
                                String cb = busText.getText().toString().trim();
                                String co = operatorText.getText().toString().trim();
                                if (!cb.isEmpty() || !co.isEmpty()) {
                                    sendData(cb.isEmpty() ? bn : cb, co.isEmpty() ? on : co, true, true);
                                }
                            });
                            builder.setNegativeButton("Cancel", null);
                            builder.show();
                        });
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
        return true;
    }

    private void populateFareSummary() {
        LinearLayout layoutFares = findViewById(R.id.layoutFares);
        if (layoutFares == null) return;
        layoutFares.removeAllViews();

        for (int ci = 0; ci < m_Card.getContract().size(); ci++) {
            Contract c = m_Card.getContract().get(ci);
            if (c.getProductCode() == 0) continue;

            LinearLayout fareRow = new LinearLayout(this);
            fareRow.setOrientation(LinearLayout.HORIZONTAL);
            fareRow.setPadding(0, 4, 0, 4);

            TextView nameView = new TextView(this);
            String name = (ci + 1) + ". " + c.getProductName();
            String detail = "";
            boolean expired = !c.isValid();

            if (c.isSubscription() && c.getValidityDate() != null) {
                detail = " until " + Utils.DateToStringShort(c.getValidityDate());
            } else if (!c.isSubscription()) {
                detail = " — " + c.getNbTicket() + " trips";
            }

            nameView.setText(name + detail);
            nameView.setTextSize(14);
            nameView.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            TextView statusView = new TextView(this);
            if (expired) {
                String tag = c.isSubscription() ? "expired" : "out";
                statusView.setText(tag);
                statusView.setTextColor(0xFFFF4444);
                nameView.setTextColor(0xFFFF4444);
            } else {
                statusView.setText("valid");
                statusView.setTextColor(0xFF4CAF50);
            }
            statusView.setTextSize(12);
            statusView.setPadding(8, 2, 8, 2);

            fareRow.addView(nameView);
            fareRow.addView(statusView);

            // Small submit/correct button
            Button submitBtn = new Button(this);
            submitBtn.setText("✏");
            submitBtn.setTextSize(12);
            submitBtn.setPadding(4, 2, 4, 2);
            submitBtn.setMinWidth(0);
            submitBtn.setMinHeight(0);
            submitBtn.setMinimumWidth(0);
            submitBtn.setMinimumHeight(0);
            submitBtn.setBackgroundColor(0x00000000); // transparent
            final int fCode = c.getProductCode();
            submitBtn.setOnClickListener(v -> {
                final int fCodeLocal = fCode;
                final boolean isKnown = !c.getProductName().startsWith("Unknown");
                if (isKnown) {
                    android.app.AlertDialog.Builder voteBuilder = new android.app.AlertDialog.Builder(this);
                    voteBuilder.setTitle("Is this correct?");
                    voteBuilder.setMessage(c.getProductName() + "\n\nProduct code 0x" + String.format("%04X", fCodeLocal));
                    voteBuilder.setPositiveButton("Yes", (dialog, which) -> {
                        submitProductName(fCodeLocal, c.getProductName());
                    });
                    voteBuilder.setNegativeButton("No", (dialog, which) -> {
                        android.app.AlertDialog.Builder corrBuilder = new android.app.AlertDialog.Builder(this);
                        corrBuilder.setTitle("Correct this product name");
                        corrBuilder.setMessage("Product code 0x" + String.format("%04X", fCodeLocal));
                        final android.widget.EditText input = new android.widget.EditText(this);
                        input.setHint("e.g. 10 trips, All modes A");
                        corrBuilder.setView(input);
                        corrBuilder.setPositiveButton("Submit", (d2, w2) -> {
                            String pn = input.getText().toString().trim();
                            if (!pn.isEmpty()) submitProductName(fCodeLocal, pn);
                        });
                        corrBuilder.setNegativeButton("Cancel", null);
                        corrBuilder.show();
                    });
                    voteBuilder.show();
                } else {
                    android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
                    builder.setTitle("Submit product name");
                    builder.setMessage("Product code 0x" + String.format("%04X", fCodeLocal));
                    final android.widget.EditText input = new android.widget.EditText(this);
                    input.setHint("e.g. 10 trips, All modes A");
                    builder.setView(input);
                    builder.setPositiveButton("Submit", (d2, w2) -> {
                        String pn = input.getText().toString().trim();
                        if (!pn.isEmpty()) submitProductName(fCodeLocal, pn);
                    });
                    builder.setNegativeButton("Cancel", null);
                    builder.show();
                }
            });

            fareRow.addView(submitBtn);
            layoutFares.addView(fareRow);
        }
    }

    private void submitProductName(int code, String name) {
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL(ServerConfig.BASE_URL + "/api/v1/products/submit");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("X-API-Key", ServerConfig.API_KEY);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                String json = "{\"product_code\":" + code + ",\"product_name\":\""
                        + name.replace("\"", "\\\"") + "\",\"operator_id\":0}";
                conn.getOutputStream().write(json.getBytes("UTF-8"));

                int respCode = conn.getResponseCode();
                String resp = new String(conn.getInputStream().readAllBytes(), "UTF-8");
                conn.disconnect();

                runOnUiThread(() -> {
                    if (respCode == 200) {
                        Toast.makeText(this, "Submitted! Thanks.", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Failed: " + resp, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ---- ActionBar menu (⋮ button) ----
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        menu.add(0, 1, 0, "Rename");
        menu.add(0, 2, 0, "Export JSON");
        menu.add(0, 3, 0, "Copy JSON");
        menu.add(0, 4, 0, "Duplicate");
        menu.add(0, 5, 0, "Delete");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1: // Rename
                renameCardDialog();
                return true;
            case 2: // Export JSON (save to file)
                exportCardJson();
                return true;
            case 3: // Copy JSON
                copyCardJson();
                return true;
            case 4: // Duplicate
                duplicateCard();
                return true;
            case 5: // Delete
                deleteCardDialog();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void renameCardDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Rename Card");
        final EditText input = new EditText(this);
        input.setText(getTitle());
        input.setSelectAllOnFocus(true);
        builder.setView(input);
        builder.setPositiveButton("Rename", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty()) {
                setTitle(newName);
                if (entryId >= 0) {
                    CardDatabase.getInstance(this).renameCard(entryId, newName);
                }
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void exportCardJson() {
        try {
            String json = m_Card.rawJson();
            String type = m_Card.getType() == Card.CardType.OPUS ? "OPUS" : "MIFARE";
            String code = String.format("%04X", m_Card.getProductCode());
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US);
            String filename = type + "_" + code + "_" + sdf.format(new java.util.Date()) + ".json";
            File path = getFilesDir();
            File jsonFile = new File(path, filename);
            FileOutputStream fos = new FileOutputStream(jsonFile);
            fos.write(json.getBytes());
            fos.close();
            Toast.makeText(this, "Saved: " + filename, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void copyCardJson() {
        try {
            String json = m_Card.rawJson();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("card_json", json);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "JSON copied", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void duplicateCard() {
        if (entryId >= 0) {
            long newId = CardDatabase.getInstance(this).duplicateCard(entryId);
            if (newId >= 0) {
                Toast.makeText(this, "Card duplicated", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Failed to duplicate", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Cannot duplicate: card not in history", Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteCardDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Delete Card");
        builder.setMessage("Delete \"" + getTitle() + "\"?");
        builder.setPositiveButton("Delete", (dialog, which) -> {
            if (entryId >= 0) {
                CardDatabase.getInstance(this).deleteCard(entryId);
            }
            finish();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }
}

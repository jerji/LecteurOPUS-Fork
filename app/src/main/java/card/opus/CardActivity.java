package card.opus;

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
import static card.opus.Utils.DOMAIN;
import static card.opus.Utils.isInternetAvailable;

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
                break;
                            case OPUS:
                                String ID = "" + m_Card.getID();
                                textID.setText("n° " + ID.substring(0,3) + " " + ID.substring(3,6) + " " + ID.substring(6));
                                textType.setText(R.string.card_opus);
                                imageType.setBackgroundResource(R.mipmap.opus);
                                textExpiration.setText(getString(R.string.expiration) + " "
                                        + Utils.DateToStringShort(m_Card.getExpirationDate()));

                                // Populate fare products summary into layoutFares
                                LinearLayout layoutFares = findViewById(R.id.layoutFares);
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
                                        statusView.setTextColor(0xFFFF4444); // red
                                        nameView.setTextColor(0xFFFF4444);
                                    } else {
                                        statusView.setText("valid");
                                        statusView.setTextColor(0xFF4CAF50); // green
                                    }
                                    statusView.setTextSize(12);
                                    statusView.setPadding(8, 2, 8, 2);

                                    fareRow.addView(nameView);
                                    fareRow.addView(statusView);
                                    layoutFares.addView(fareRow);
                                }
                                break;
                            default:
                // do nothing
                break;
        }

        // Contract details — show all contracts with status
        int compteurTicket = 0;
        ArrayList<Contract> contract = m_Card.getContract();
        for (int i = 0; i < contract.size(); i++) {
            Contract c = contract.get(i);
            boolean valid = c.isValid();
            if (c.isSubscription()) {
                // Subscription
                c.setLogoFromXml(this);
                View customSubs = inflater.inflate(R.layout.subscription, null);
                LinearLayout LayoutSubsTime =
                        (LinearLayout) customSubs.findViewById(R.id.layoutSubsTime);
                TextView textSubsTime =
                        (TextView) customSubs.findViewById(R.id.subscriptionTime);
                TextView textSubsDate =
                        (TextView) customSubs.findViewById(R.id.subscriptionDate);
                ImageView logo = (ImageView) customSubs.findViewById(R.id.logoSubs);
                TextView textSubsType = (TextView) customSubs.findViewById(R.id.textSubscriptionType);

                textSubsType.setText(c.getProductName());
                if (c.getValidityDate() != null) {
                    textSubsDate.setText(Utils.DateToString(c.getValidityDate()));
                }
                if (!valid) {
                    textSubsType.append(" (expired)");
                    textSubsType.setTextColor(0xFFFF4444); // red
                }
                LayoutSubsTime.setVisibility(View.GONE);
                int logoId = c.getLogoId();
                if (logoId != 0) logo.setImageResource(logoId);
                LayoutSubscription.addView(customSubs);
                compteurTicket++;
            }
            else {
                // Ticket
                c.setLogoFromXml(this);
                View customTicket = inflater.inflate(R.layout.ticket, null);
                TextView textTicket = (TextView) customTicket.findViewById(R.id.NbTicket);
                ImageView logo = (ImageView) customTicket.findViewById(R.id.logoTicket);
                int nbTicket = c.getNbTicket();

                String ticket;
                if (!valid) {
                    ticket = c.getProductName() + " — 0 trips (out)";
                } else if (Locale.getDefault().getLanguage().equalsIgnoreCase("ru")) {
                    if (nbTicket <= 0) {
                        ticket = c.getProductName() + " — " + nbTicket + " " + getString(R.string.ticket_remaining5);
                    } else {
                        ticket = c.getProductName() + " — " + nbTicket + " " + pluralForm(nbTicket);
                    }
                } else {
                    if (nbTicket == 1) {
                        ticket = c.getProductName() + " — " + nbTicket + " " + getString(R.string.ticket_remaining1);
                    } else {
                        ticket = c.getProductName() + " — " + nbTicket + " " + getString(R.string.ticket_remaining2);
                    }
                }

                textTicket.setText(ticket);
                if (!valid) textTicket.setTextColor(0xFFFF4444); // red
                int logoId = c.getLogoId();
                if (logoId != 0 && m_Card.getType() == Card.CardType.OPUS) logo.setImageResource(logoId);
                LayoutTicket.addView(customTicket);
                compteurTicket++;
            }
        }

        if (compteurTicket == 0){ // 0 tickets
            View customTicket = inflater.inflate(R.layout.ticket, null);
            TextView textTicket = (TextView) customTicket.findViewById(R.id.NbTicket);
            textTicket.setText("0 " + getString(R.string.ticket_remaining5));
            LayoutTicket.addView(customTicket);
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
            final LayoutInflater layoutInflater = LayoutInflater.from(this);
            View promptView = layoutInflater.inflate(R.layout.input_dialog, null);
            alertDialogBuilder.setView(promptView);

            final Spinner operatorSpinner = (Spinner) promptView.findViewById(R.id.operatorInput);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                    Utils.getOperatorNames(this));
            operatorSpinner.setAdapter(adapter);

            operatorSpinner.setSelection(adapter.getPosition(operatorName));

            final Spinner busSpinner = (Spinner) promptView.findViewById(R.id.busInput);
            final String[] items = new String[]{""};
            adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items);
            busSpinner.setAdapter(adapter);

            final EditText busInputText = (EditText) promptView.findViewById(R.id.busText);
            final EditText operatorInputText = (EditText) promptView.findViewById(R.id.operatorText);
            final TextView busLabel = (TextView) promptView.findViewById(R.id.busLabel);
            final LinearLayout busLayout = (LinearLayout) promptView.findViewById(R.id.busLayout);

            operatorSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {

                @Override
                public void onItemSelected(AdapterView<?> spinner, View container,
                                           int position, long id) {
                    String operator = (String) spinner.getSelectedItem();
                    String items[];
                    if (operator.equals("")){
                        busLabel.setVisibility(View.GONE);
                        busLayout.setVisibility(View.GONE);
                        operatorInputText.setVisibility(View.GONE);
                        items = new String[]{""};
                    }
                    else if(operator.equals(getString(R.string.other))){
                        operatorInputText.setVisibility(View.VISIBLE);
                        busLabel.setVisibility(View.VISIBLE);
                        busLayout.setVisibility(View.VISIBLE);
                        items = new String[]{getString(R.string.other)};
                    }
                    else{
                        operatorInputText.setVisibility(View.GONE);
                        busLabel.setVisibility(View.VISIBLE);
                        busLayout.setVisibility(View.VISIBLE);
                        items = Utils.getBusNames(spinner.getContext(), operator);
                    }
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(spinner.getContext(),
                            android.R.layout.simple_spinner_dropdown_item, items);
                    busSpinner.setAdapter(adapter);
                }

                @Override
                public void onNothingSelected(AdapterView<?> arg0) {
                    // TODO Auto-generated method stub
                }
            });

            busSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {

                @Override
                public void onItemSelected(AdapterView<?> spinner, View container,
                                           int position, long id) {
                    String bus = (String) spinner.getSelectedItem();
                    if(bus.equals(getString(R.string.other))) {
                        busInputText.setVisibility(View.VISIBLE);
                    }
                    else{
                        busInputText.setVisibility(View.GONE);
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> arg0) {
                    // TODO Auto-generated method stub
                }
            });

            alertDialogBuilder.setPositiveButton(R.string.send, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    boolean missing = false;
                    String operator = (String) operatorSpinner.getSelectedItem();
                    if (operator.equals(getString(R.string.other))) {
                        operator = operatorInputText.getText().toString();
                        missing = true;
                    }

                    String bus = (String) busSpinner.getSelectedItem();
                    if (bus.equals(getString(R.string.other))) {
                        bus = busInputText.getText().toString();
                        missing = true;
                    }

                    if(sendData(bus, operator, true, missing))
                        m_AddButtons.get(index).setVisibility(View.GONE);
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
        String androidId = Settings.Secure.getString(this.getContentResolver(), Settings.Secure.ANDROID_ID);
        // Send POST request
        // "http://ec2-54-149-222-116.us-west-2.compute.amazonaws.com/"
        String data;
        String[] params;

        if(m_OperatorId == 0 || m_BusId == 0){
            data = "id=" + androidId + "&time=" + m_TimeHash + "&busName=" + busName
                    + "&operatorName=" + operatorName + "&cardData="
                    + m_Card.getRawSerialize().replaceAll("\n", "");
            params = new String[]{"https://" + DOMAIN + "/opus/card.php", data};
        }
        else{
            data = "id=" + androidId + "&time=" + m_TimeHash + "&busId=" + m_BusId + "&operatorId=" + m_OperatorId
                    + "&busName=" + busName + "&operatorName=" + operatorName + "&add=" + Boolean.toString(add);
            params = new String[]{"https://" + DOMAIN + "/opus/add.php", data};
        }

        if(missing)
            params[0] = "https://" + DOMAIN + "/opus/missing.php";
        SendHttpRequestTask t = new SendHttpRequestTask();
        t.execute(params);

        BufferedWriter bw = null;
        try {
            bw = new BufferedWriter(new FileWriter(m_PreviousAddTrip, true));
            bw.write(m_TimeHash);
            bw.newLine();
            bw.flush();
        }
        catch (IOException e){
            Log.e(TAG, "Error writing file");
        }
        finally {
            if (bw != null) try {
                bw.close();
            } catch (IOException e) {
                // Do nothing
            }
        }
        Toast.makeText(this, R.string.thank_contribution, Toast.LENGTH_SHORT).show();
        return true;
    }

    private class SendHttpRequestTask extends AsyncTask<String, Void, String> {
        @Override
        protected String doInBackground(String... params) {
            String url = params[0];
            String name = params[1];

            String data = sendHttpRequest(url, name);
            return data;
        }

        @Override
        protected void onPostExecute(String result) {

        }
    }

    private String sendHttpRequest(String url, String data) {
        StringBuffer buffer = new StringBuffer();
        try {
            System.out.println("URL ["+url+"] - Param ["+data+"]");

            HttpURLConnection con = (HttpURLConnection) ( new URL(url)).openConnection();
            con.setRequestMethod("POST");
            con.setDoInput(true);
            con.setDoOutput(true);
            con.connect();
            con.getOutputStream().write( (data).getBytes());

            InputStream is = con.getInputStream();
            byte[] b = new byte[1024];

            while ( is.read(b) != -1)
                buffer.append(new String(b));

            con.disconnect();
        }
        catch(Throwable t) {
            t.printStackTrace();
        }

        return buffer.toString();
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

package card.opus;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

public class CardDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "card_history.db";
    private static final int DB_VERSION = 1;

    private static final String TABLE = "scanned_cards";
    private static final String COL_ID = "id";
    private static final String COL_SCAN_TIME = "scan_time";
    private static final String COL_CARD_TYPE = "card_type";
    private static final String COL_PRODUCT_CODE = "product_code";
    private static final String COL_PRODUCT_NAME = "product_name";
    private static final String COL_RAW_XML = "raw_xml";
    private static final String COL_UID = "uid";
    private static final String COL_OPERATOR = "operator_id";

    private static CardDatabase instance;

    public static synchronized CardDatabase getInstance(Context ctx) {
        if (instance == null) {
            instance = new CardDatabase(ctx.getApplicationContext());
        }
        return instance;
    }

    private CardDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                COL_SCAN_TIME + " INTEGER NOT NULL," +
                COL_CARD_TYPE + " TEXT," +
                COL_PRODUCT_CODE + " INTEGER DEFAULT 0," +
                COL_PRODUCT_NAME + " TEXT," +
                COL_RAW_XML + " TEXT," +
                COL_UID + " TEXT," +
                COL_OPERATOR + " INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    public long saveCard(Card card) {
        SQLiteDatabase db = getWritableDatabase();
        String rawXml = card.getRawSerialize();
        if (rawXml == null || rawXml.isEmpty()) return -1;

        String type = card.getType().name();
        int productCode = card.getProductCode();
        String productName = "Unknown";
        int operatorId = 0;
        String uid = "";

        if (card.getContract() != null && card.getContract().size() > 0) {
            Contract c = card.getContract().get(0);
            productName = c.getProductName();
            operatorId = c.getOperatorId();
        }

        SQLiteStatement stmt = db.compileStatement(
                "INSERT INTO " + TABLE + " (" +
                COL_SCAN_TIME + "," + COL_CARD_TYPE + "," + COL_PRODUCT_CODE + "," +
                COL_PRODUCT_NAME + "," + COL_RAW_XML + "," + COL_UID + "," + COL_OPERATOR +
                ") VALUES (?,?,?,?,?,?,?)");

        stmt.bindLong(1, System.currentTimeMillis());
        stmt.bindString(2, type);
        stmt.bindLong(3, productCode);
        stmt.bindString(4, productName);
        stmt.bindString(5, rawXml);
        stmt.bindString(6, uid);
        stmt.bindLong(7, operatorId);

        long id = stmt.executeInsert();
        stmt.close();
        return id;
    }

    public List<CardEntry> getRecentCards(int limit) {
        List<CardEntry> cards = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT * FROM " + TABLE + " ORDER BY " + COL_SCAN_TIME + " DESC LIMIT ?",
                new String[]{String.valueOf(limit)});

        while (c.moveToNext()) {
            CardEntry entry = new CardEntry();
            entry.id = c.getLong(c.getColumnIndexOrThrow(COL_ID));
            entry.scanTime = c.getLong(c.getColumnIndexOrThrow(COL_SCAN_TIME));
            entry.cardType = c.getString(c.getColumnIndexOrThrow(COL_CARD_TYPE));
            entry.productCode = c.getInt(c.getColumnIndexOrThrow(COL_PRODUCT_CODE));
            entry.productName = c.getString(c.getColumnIndexOrThrow(COL_PRODUCT_NAME));
            entry.rawXml = c.getString(c.getColumnIndexOrThrow(COL_RAW_XML));
            entry.uid = c.getString(c.getColumnIndexOrThrow(COL_UID));
            entry.operatorId = c.getInt(c.getColumnIndexOrThrow(COL_OPERATOR));
            cards.add(entry);
        }
        c.close();
        return cards;
    }

    public long importFromJson(String json) {
        try {
            org.json.JSONObject obj = new org.json.JSONObject(json);
            int productCode = 0;
            String type = "OPUS";
            String rawXml = null;

            // Always extract productCode and type
            String pcStr = obj.optString("productCode", "0000");
            if (pcStr.startsWith("0x")) pcStr = pcStr.substring(2);
            try { productCode = Integer.parseInt(pcStr, 16); } catch (Exception e) { productCode = 0; }
            type = obj.optString("type", "OPUS");

            // Use rawXml if available (full round-trip)
            if (obj.has("rawXml")) {
                rawXml = obj.getString("rawXml");
                // Unescape JSON string escaping
                rawXml = rawXml.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
            }

            if (rawXml == null || rawXml.isEmpty()) {
                // Build minimal XML from decoded fields

                StringBuilder xml = new StringBuilder();
                String rootTag = type.equals("OCCASIONNELLE") ? "Mifare" : "Opus";
                xml.append("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>");
                xml.append("<").append(rootTag).append(">");

                if (type.equals("OCCASIONNELLE")) {
                    org.json.JSONArray pages = obj.optJSONArray("pages");
                    if (pages != null) {
                        xml.append("<Pages>");
                        for (int i = 0; i < pages.length() && i < 16; i++) {
                            xml.append("<List index=\"").append(i).append("\">")
                               .append(pages.optString(i, "00000000")).append("</List>");
                        }
                        xml.append("</Pages>");
                    }
                } else {
                    xml.append("<ID>0000000000000000000000000000000000000000000000000000000000000000</ID>");
                    xml.append("<Expiration>0000000000000000000000000000000000000000000000000000000000000000</Expiration>");
                }
                xml.append("</").append(rootTag).append(">");
                rawXml = xml.toString();
            }

            SQLiteDatabase db = getWritableDatabase();
            SQLiteStatement stmt = db.compileStatement(
                    "INSERT INTO " + TABLE + " (" +
                    COL_SCAN_TIME + "," + COL_CARD_TYPE + "," + COL_PRODUCT_CODE + "," +
                    COL_PRODUCT_NAME + "," + COL_RAW_XML + "," + COL_UID + "," + COL_OPERATOR +
                    ") VALUES (?,?,?,?,?,?,?)");
            stmt.bindLong(1, System.currentTimeMillis());
            stmt.bindString(2, type);
            stmt.bindLong(3, productCode);
            stmt.bindString(4, "Imported (" + String.format("%04X", productCode) + ")");
            stmt.bindString(5, rawXml);
            stmt.bindString(6, obj.optString("uid", ""));
            stmt.bindLong(7, obj.optInt("operator", 0));
            long id = stmt.executeInsert();
            stmt.close();
            return id;
        } catch (Exception e) {
            return -1;
        }
    }

    public static class CardEntry {
        public long id;
        public long scanTime;
        public String cardType;
        public int productCode;
        public String productName;
        public String rawXml;
        public String uid;
        public int operatorId;

        public String getDisplayName() {
            if (productName != null && !productName.equals("Unknown")) {
                return productName;
            }
            if (productCode != 0) {
                return "Code 0x" + String.format("%04X", productCode);
            }
            return cardType;
        }
    }
}
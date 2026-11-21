package card.opus;

import android.content.Context;
import android.content.res.XmlResourceParser;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;

/**
 * Created by etienned on 12/28/16.
 */
public class Contract implements Serializable {
    public static final String TAG = "Contract";
    private boolean m_Subscription;
    private boolean m_Valid;
    private int m_nbTicket;
    private int m_operatorId;
    private String m_operatorName;
    private int m_logoId;
    private Calendar m_ValidityDate;
    private int productCode;
    private int contractGeneralBitmap;
    private int contractProvider;
    private int contractTariff;
    private int saleAgent;
    private int usedCounter;
    private int useLimit;
    private int score;
    private Calendar saleDate;
    private int contractContext;
    private String rawHex; // full raw hex of the contract record

    public int getContractProvider() { return contractProvider; }
    public int getContractGeneralBitmap() { return contractGeneralBitmap; }
    public int getContractTariff() { return contractTariff; }
    public int getSaleAgent() { return saleAgent; }
    public int getUsedCounter() { return usedCounter; }
    public int getUseLimit() { return useLimit; }
    public int getScore() { return score; }
    public Calendar getSaleDate() { return saleDate; }
    public int getContractContext() { return contractContext; }
    public String getRawHex() { return rawHex; }

    public Contract(byte[] subscriptionData, byte[] ticketData){ // OPUS
        // Store raw hex
        StringBuilder hex = new StringBuilder();
        for (byte b : subscriptionData) hex.append(String.format("%02X", b));
        rawHex = hex.toString();

        m_operatorId = Utils.bytesToInt(subscriptionData, 9, 8);
        
        // Spirtech alias_Contract format — full bit-by-bit decode:
        int offset = 0;
        
        // 1. ContractGeneralBitmap (7 bits)
        int genBitmap = Utils.bytesToInt(subscriptionData, offset, 7);
        contractGeneralBitmap = genBitmap;
        offset += 7;
        
        // 2. ContractProvider (8 bits, conditional on bitmap bit 0)
        if ((genBitmap & 0x01) != 0) {
            contractProvider = Utils.bytesToInt(subscriptionData, offset, 8);
            if (contractProvider != 0) m_operatorId = contractProvider;
            offset += 8;
        } else {
            offset += 8;
        }
        
        // 3. ContractTariff (16 bits, conditional on bitmap bit 1)
        if ((genBitmap & 0x02) != 0) {
            contractTariff = Utils.bytesToInt(subscriptionData, offset, 16);
            this.productCode = contractTariff;
            offset += 16;
        } else {
            offset += 16;
        }
        
        // 4. ContractValidityInfoBitmap (2 bits)
        int validBitmap = Utils.bytesToInt(subscriptionData, offset, 2); offset += 2;
        
        // 5. ContractValidityStartDate (14 bits, conditional on valid bitmap bit 0)
        if ((validBitmap & 0x01) != 0 && subscriptionData.length * 8 >= offset + 14) {
            int startDays = Utils.bytesToInt(subscriptionData, offset, 14);
            if (startDays > 0) m_ValidityDate = Utils.intToDateTime(startDays, 0);
            offset += 14;
        } else { offset += 14; }
        
        // 6. ContractValidityEndDate (14 bits, conditional on valid bitmap bit 1)
        if ((validBitmap & 0x02) != 0 && subscriptionData.length * 8 >= offset + 14) {
            int endDays = Utils.bytesToInt(subscriptionData, offset, 14);
            // Store as validity date if we don't have one yet (0 = no date set)
            if (m_ValidityDate == null && endDays > 0) {
                m_ValidityDate = Utils.intToDateTime(endDays, 0);
            }
            offset += 14;
        } else { offset += 14; }
        
        // 7. ContractDataBitmap (9 bits)
        int dataBitmap = Utils.bytesToInt(subscriptionData, offset, 9); offset += 9;
        
        // 8-13. Remaining Spirtech data fields (always present):
        // SaleAgent: 6 bits
        saleAgent = Utils.bytesToInt(subscriptionData, offset, 6); offset += 6;
        // ContractUsedCounter: 6 bits
        usedCounter = Utils.bytesToInt(subscriptionData, offset, 6); offset += 6;
        // ContractUseLimit: 6 bits
        useLimit = Utils.bytesToInt(subscriptionData, offset, 6); offset += 6;
        // Score: 6 bits
        score = Utils.bytesToInt(subscriptionData, offset, 6); offset += 6;
        // ContractSaleDate: 14 bits
        int saleDays = Utils.bytesToInt(subscriptionData, offset, 14);
        if (saleDays > 0) saleDate = Utils.intToDateTime(saleDays, 0);
        offset += 14;
        // ContractContext: 8 bits
        contractContext = Utils.bytesToInt(subscriptionData, offset, 8); offset += 8;
        
        // Determine ticket/subscription type from remaining data
        if(Utils.bytesToInt(subscriptionData, 40, 16) == 0){
            // Ticket
            m_Subscription = false;
            m_nbTicket = Utils.bytesToInt(ticketData, 16, 8);
            m_Valid = (m_nbTicket > 0);
            // Check ValidityEndDate for ticket expiry too (0 days = no expiry set)
            if (m_Valid && m_ValidityDate != null) {
                Calendar today = Calendar.getInstance();
                // Skip epoch dates (1997-01-01 = day 0, means no expiry)
                if (m_ValidityDate.get(Calendar.YEAR) > 1997) {
                    m_Valid = m_ValidityDate.compareTo(today) > 0;
                }
            }
        }
        else{
            // Subscription
            m_Subscription = true;
            Calendar today = Calendar.getInstance();
            int days = Utils.bytesToInt(subscriptionData, 47, 14);
            m_ValidityDate = Utils.intToDateTime(days, 0);
            m_Valid = (m_ValidityDate.compareTo(today) > 0);
        }
    }

    public Contract(byte[] page3, byte[] page4, byte[] page5, byte[] page10) { // Mifare
        m_nbTicket = 0;
        m_Subscription = false;
        m_operatorId = Utils.bytesToInt(page5, 18, 8);

        // Extract product code from page 5 bytes 0-1
        this.productCode = ((page5[0] & 0xFF) << 8) | (page5[1] & 0xFF);

        if (Arrays.equals(page3, Utils.HexStringToByteArray("00000000"))) {
            if (!Arrays.equals(page10, Utils.HexStringToByteArray("00000000"))) {
                // Carte neuve (page 3 zeroed, page 10 has ticket count)
                m_nbTicket = page10[2] & 0x80 >> 7 |
                        (page10[1] & 0x0F) << 1;
                if ((Arrays.equals(page4, Utils.HexStringToByteArray("00010001")))
                        &&  m_nbTicket != 0) {
                    m_nbTicket -= 1;
                }
                m_Valid = m_nbTicket > 0;
            } else {
                // Page 3 zeroed, page 10 zeroed — no bitmap info
                // Use product code to determine ticket count
                m_Valid = true;
            }
        }
        else if (Arrays.equals(page3, Utils.HexStringToByteArray("80000000"))){
            // Subscription
            m_Subscription = true;
            m_Valid = false;
        }
        else{
            // Card used — count unused tickets from page 3 bitmap
            m_Valid = true;
            while(m_nbTicket < 32 && Utils.bytesToInt(page3,31 - m_nbTicket, 1) == 0){
                m_nbTicket += 1;
            }
        }

        // For known single-trip products, cap ticket count at 1
        // Bit 31 of page 3 = 0 means trip available, 1 means used
        if (getProductName(this.productCode).toLowerCase().contains("1trip")) {
            int bit31 = Utils.bytesToInt(page3, 31, 1);
            m_nbTicket = bit31 == 0 ? 1 : 0;
            m_Valid = m_nbTicket > 0;
        }
    }

    public boolean isValid() {
        return m_Valid;
    }

    public void setLogoFromXml(Context ctx){
        String node = "";
        String logo = "";
        m_operatorName = "";
        XmlResourceParser operatorXml = ctx.getResources().getXml(R.xml.operators);
        try {
            int event = operatorXml.getEventType();
            outerloop:
            while (event != XmlPullParser.END_DOCUMENT){
                switch (event) {
                    case XmlPullParser.START_TAG:
                        node = operatorXml.getName();
                        if(node.equals("operator")) {
                            if (operatorXml.getAttributeValue(null, "id").equals("" + m_operatorId)) {
                                logo = operatorXml.getAttributeValue(null, "logo");
                                m_operatorName = operatorXml.getAttributeValue(null, "name");
                                break outerloop;
                            }
                        }
                        break;
                }
                event = operatorXml.next();
            }
        } catch (Exception e) {
            Log.e("CardActivity", "Error parsing stations XML file: " + e.getMessage());
        }

        m_logoId = 0;
        if (!logo.equals("")){
            m_logoId = ctx.getResources().getIdentifier(logo, "drawable", ctx.getPackageName());
        }
    }

    public int getNbTicket() {
        return m_nbTicket;
    }

    public int getOperatorId(){
        return m_operatorId;
    }

    public boolean isSubscription() {
        return m_Subscription;
    }

    public Calendar getValidityDate() {
        return m_ValidityDate;
    }

    public int getLogoId() {
        return m_logoId;
    }

    public String getOperatorName() {
        return m_operatorName;
    }

    public void setProductCode(int code) {
        this.productCode = code;
    }

    public int getProductCode() {
        return productCode;
    }

    private static HashMap<Integer, String> productNameCache = null;

    public static void loadProductCodes(Context ctx) {
        if (productNameCache != null) return;
        productNameCache = new HashMap<>();
        try {
            XmlResourceParser xml = ctx.getResources().getXml(R.xml.product_codes);
            int event = xml.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && xml.getName().equals("product")) {
                    String codeStr = xml.getAttributeValue(null, "code");
                    String name = xml.getAttributeValue(null, "name");
                    if (codeStr != null && name != null) {
                        int code = Integer.parseInt(codeStr, 16);
                        productNameCache.put(code, name);
                    }
                }
                event = xml.next();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading product codes: " + e.getMessage());
            // Keep fallback switch
        }
    }

    public String getProductName() {
        return getProductName(productCode);
    }

    private static String getProductName(int code) {
        // Loaded from res/xml/product_codes.xml by Contract.loadProductCodes()
        if (productNameCache != null) {
            String name = productNameCache.get(code);
            if (name != null) return name;
        }
        return String.format("Unknown (code %04X)", code);
    }
}

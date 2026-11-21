package card.opus;

import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.Serializable;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;

import static card.opus.Utils.DateToStringNumber;
import static card.opus.Utils.TimeToString;

/**
 * Created by etienned on 10/7/16.
 */
public class Card implements Serializable {

    public static final String TAG = "CARD";

    public enum SubscriptionType {
        DAY,
        WEEK,
        MONTH,
        TICKET,
    }

     public enum CardType {
            OPUS,
            OCCASIONNELLE,
     }

    private SubscriptionType m_Subscription; // Passe d'un jour, mois...
    private ArrayList<Trip> m_Trip;
    private ArrayList<Contract> m_Contract;
    private CardType m_Type;
    private Calendar m_ExpirationDate;
    private long m_ID;
    private String m_serializeRawCard;
    private int productCode;

    public Card(){
        // do nothing
    }

    public static Card fromRawSerialize(String rawXml) {
        if (rawXml == null || rawXml.isEmpty()) return null;
        try {
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(rawXml.getBytes("UTF-8"));
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document doc = builder.parse(bais);

            if (rawXml.contains("<Mifare>")) {
                // MIFARE UL: parse pages from XML
                org.w3c.dom.NodeList pages = doc.getElementsByTagName("List");
                byte[][] pageData = new byte[16][4];
                for (int i = 0; i < 16; i++) pageData[i] = new byte[4];
                for (int i = 0; i < pages.getLength(); i++) {
                    String idxStr = pages.item(i).getAttributes().getNamedItem("index").getNodeValue();
                    String hex = pages.item(i).getTextContent().trim();
                    int idx = Integer.parseInt(idxStr);
                    if (idx >= 0 && idx < 16) {
                        pageData[idx] = hexStringToBytes(hex);
                    }
                }
                Mifare mifare = new Mifare(pageData);
                return mifare.getCard();
            } else if (rawXml.contains("<Opus>")) {
                // OPUS card: parse sections from XML
                org.w3c.dom.NodeList idNodes = doc.getElementsByTagName("ID");
                org.w3c.dom.NodeList expNodes = doc.getElementsByTagName("Expiration");
                org.w3c.dom.NodeList transitNodes = doc.getElementsByTagName("Transit");
                org.w3c.dom.NodeList subNodes = doc.getElementsByTagName("Subscription");
                org.w3c.dom.NodeList ticketNodes = doc.getElementsByTagName("Ticket");

                byte[] dataID = hexStringToBytes(idNodes.item(0).getTextContent().trim());
                byte[] dataExp = hexStringToBytes(expNodes.item(0).getTextContent().trim());

                // Parse transit entries
                byte[][] dataTransit = new byte[3][];
                if (transitNodes.getLength() > 0) {
                    org.w3c.dom.NodeList transitList = transitNodes.item(0).getChildNodes();
                    int idx = 0;
                    for (int i = 0; i < transitList.getLength() && idx < 3; i++) {
                        if (transitList.item(i).getNodeName().equals("List")) {
                            dataTransit[idx++] = hexStringToBytes(
                                transitList.item(i).getTextContent().trim());
                        }
                    }
                }

                // Parse subscription entries
                byte[][] dataSubscription = new byte[4][];
                if (subNodes.getLength() > 0) {
                    org.w3c.dom.NodeList subList = subNodes.item(0).getChildNodes();
                    int idx = 0;
                    for (int i = 0; i < subList.getLength() && idx < 4; i++) {
                        if (subList.item(i).getNodeName().equals("List")) {
                            dataSubscription[idx++] = hexStringToBytes(
                                subList.item(i).getTextContent().trim());
                        }
                    }
                }

                // Parse ticket entries
                byte[][] dataTicket = new byte[4][];
                if (ticketNodes.getLength() > 0) {
                    org.w3c.dom.NodeList ticketList = ticketNodes.item(0).getChildNodes();
                    int idx = 0;
                    for (int i = 0; i < ticketList.getLength() && idx < 4; i++) {
                        if (ticketList.item(i).getNodeName().equals("List")) {
                            dataTicket[idx++] = hexStringToBytes(
                                ticketList.item(i).getTextContent().trim());
                        }
                    }
                }

                Opus opus = new Opus(dataID, dataExp, dataTransit, dataSubscription, dataTicket);
                return opus.getCard();
            }
        } catch (Exception e) {
            Log.e(TAG, "fromRawSerialize error: " + e.getMessage());
        }
        return null;
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

    public Card(CardType p_Type, long p_ID, Calendar p_ExpirationDate,
                ArrayList<Trip> p_Trip,  ArrayList<Contract> p_Contract, String serialize) {
        m_Type = p_Type;
        m_ID = p_ID;
        m_ExpirationDate = p_ExpirationDate;
        m_Trip = sortTrips(p_Trip);
        m_Contract = p_Contract;
        m_serializeRawCard = serialize;
    }

    public Card(CardType p_Type, ArrayList<Trip> p_Trip, ArrayList<Contract> p_Contract, String serialize) {
        m_Type = p_Type;
        m_Contract = p_Contract;
        m_Trip = sortTrips(p_Trip);
        m_serializeRawCard = serialize;
    }


    public ArrayList<Trip> getTrip(){
        return m_Trip;
    }

    public ArrayList<Contract> getContract(){
        return m_Contract;
    }

    public void setSubscription(SubscriptionType p_Subscription){
        m_Subscription = p_Subscription;
    }

    public ArrayList<Trip> sortTrips(ArrayList<Trip> p_Trip){
        // Sorting

        Collections.sort(p_Trip, new Comparator<Trip>() {
            @Override
            public int compare(Trip trip2, Trip trip1)
            {

                return  trip1.getDateTime().compareTo(trip2.getDateTime());
            }
        });
        return p_Trip;
    }
    public void setExpirationDate(Calendar p_ExpirationDate){
        m_ExpirationDate = p_ExpirationDate;
    }
    public void setType(CardType p_Type){
        m_Type = p_Type;
    }
    public Calendar getExpirationDate(){
        return m_ExpirationDate;
    }

    public CardType getType(){
        return m_Type;
    }
    public long getID() {
        return m_ID;
    }
    public void setID(long p_ID){
        m_ID = p_ID;
    }

    public String getRawSerialize(){
        // This return the raw data in xml form
        return m_serializeRawCard;
    }

    public void setProductCode(int code) {
        this.productCode = code;
    }

    public int getProductCode() {
        return productCode;
    }

    public String rawJson() {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"type\":\"").append(m_Type.name()).append("\"");
        json.append(",\"id\":").append(m_ID);
        if (m_ExpirationDate != null) {
            json.append(",\"expirationDate\":\"").append(DateToStringNumber(m_ExpirationDate)).append("\"");
        }
        json.append(",\"productCode\":\"").append(String.format("%04X", productCode)).append("\"");
        // Trips
        json.append(",\"trips\":[");
        for (int i = 0; i < m_Trip.size(); i++) {
            if (i > 0) json.append(",");
            Trip trip = m_Trip.get(i);
            json.append("{");
            json.append("\"index\":").append(i);
            if (trip.getDateTime() != null) {
                json.append(",\"date\":\"").append(DateToStringNumber(trip.getDateTime())).append("\"");
                json.append(",\"time\":\"").append(TimeToString(trip.getDateTime())).append("\"");
            }
            json.append(",\"transfer\":").append(trip.getTransfer());
            json.append(",\"operatorId\":").append(trip.getOperatorId());
            json.append(",\"busId\":").append(trip.getBusId());
            if (trip.getOperatorName() != null)
                json.append(",\"operatorName\":\"").append(trip.getOperatorName()).append("\"");
            if (trip.getBusName() != null)
                json.append(",\"busName\":\"").append(trip.getBusName()).append("\"");
            json.append("}");
        }
        json.append("]");
        // Contracts
        json.append(",\"contracts\":[");
        for (int i = 0; i < m_Contract.size(); i++) {
            if (i > 0) json.append(",");
            Contract contract = m_Contract.get(i);
            json.append("{");
            json.append("\"index\":").append(i);
            json.append(",\"subscription\":").append(contract.isSubscription());
            json.append(",\"valid\":").append(contract.isValid());
            json.append(",\"nbTicket\":").append(contract.getNbTicket());
            json.append(",\"operatorId\":").append(contract.getOperatorId());
            if (contract.getOperatorName() != null)
                json.append(",\"operatorName\":\"").append(contract.getOperatorName()).append("\"");
            if (contract.getValidityDate() != null)
                json.append(",\"validityDate\":\"").append(DateToStringNumber(contract.getValidityDate())).append("\"");
            json.append(",\"productCode\":\"").append(String.format("%04X", contract.getProductCode())).append("\"");
            json.append(",\"productName\":\"").append(contract.getProductName()).append("\"");
            json.append("}");
        }
        json.append("]");
        // Include raw XML for full round-trip import
        if (m_serializeRawCard != null) {
            json.append(",\"rawXml\":\"")
                .append(m_serializeRawCard.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"))
                .append("\"");
        }
        json.append("}");
        return json.toString();
    }

    public String serialize() {
        // This return the data in xml form

        try {
            XmlSerializer xmlSerializer = Xml.newSerializer();
            StringWriter writer = new StringWriter();

            xmlSerializer.setOutput(writer);

            //Start Document
            xmlSerializer.startDocument("UTF-8", true);
            //Open Tag <file>
            xmlSerializer.startTag("", "Card");

            xmlSerializer.startTag("", "Type");
            xmlSerializer.text(m_Type.name());
            xmlSerializer.endTag("", "Type");

            xmlSerializer.startTag("", "ID");
            xmlSerializer.text(Long.toString(m_ID));
            xmlSerializer.endTag("", "ID");

            if (m_ExpirationDate != null) {
                xmlSerializer.startTag("", "ExpirationDate");
                xmlSerializer.text(DateToStringNumber(m_ExpirationDate));
                xmlSerializer.endTag("", "ExpirationDate");
            }

            xmlSerializer.startTag("", "Trip");
            for (int i = 0; i < m_Trip.size(); i++) {
                Trip trip = m_Trip.get(i);
                xmlSerializer.startTag("", "List");
                xmlSerializer.attribute("", "index", Integer.toString(i));

                if (trip.getDateTime() != null) {
                    xmlSerializer.attribute("", "Date", DateToStringNumber(trip.getDateTime()));
                    xmlSerializer.attribute("", "Time", TimeToString(trip.getDateTime()));
                }
                xmlSerializer.attribute("", "Transfert", Boolean.toString(trip.getTransfer()));
                xmlSerializer.attribute("", "OperatorId", Integer.toString(trip.getOperatorId()));
                xmlSerializer.attribute("", "BusId", Integer.toString(trip.getBusId()));
                if (trip.getOperatorName() != null)
                    xmlSerializer.attribute("", "OperatorName", trip.getOperatorName());
                if (trip.getBusName() != null)
                    xmlSerializer.attribute("", "BusName", trip.getBusName());

                xmlSerializer.endTag("", "List");
            }
            xmlSerializer.endTag("", "Trip");

            xmlSerializer.startTag("", "Contract");
            for (int i = 0; i < m_Contract.size(); i++) {
                Contract contract = m_Contract.get(i);
                xmlSerializer.startTag("", "List");
                xmlSerializer.attribute("", "index", Integer.toString(i));

                xmlSerializer.attribute("", "Subscription", Boolean.toString(contract.isSubscription()));
                xmlSerializer.attribute("", "Valid", Boolean.toString(contract.isValid()));
                xmlSerializer.attribute("", "NbTicket", Integer.toString(contract.getNbTicket()));
                xmlSerializer.attribute("", "OperatorId", Integer.toString(contract.getOperatorId()));
                if (contract.getOperatorName() != null)
                    xmlSerializer.attribute("", "OperatorName", contract.getOperatorName());
                if (contract.getValidityDate() != null)
                    xmlSerializer.attribute("", "ValidityDate", DateToStringNumber(contract.getValidityDate()));

                xmlSerializer.endTag("", "List");
            }
            xmlSerializer.endTag("", "Contract");

            xmlSerializer.endTag("", "Card");
            xmlSerializer.endDocument();

            return writer.toString();
        }
        catch (IOException e){
            Log.e(TAG, "Error serialize");
            return "";
        }
    }
}
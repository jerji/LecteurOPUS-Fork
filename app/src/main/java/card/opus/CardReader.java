/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package card.opus;

import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareUltralight;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcB;
import android.util.Log;

import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * Callback class, invoked when an NFC card is scanned while the device is running in reader mode.
 *
 * Reader mode can be invoked by calling NfcAdapter
 */
public class CardReader implements NfcAdapter.ReaderCallback {
    private static final String TAG = "CardReader";

    // ISO-DEP command HEADER for selecting an AID.
    // Format: [Class | Instruction | Parameter 1 | Parameter 2]
    private static final String SELECT_APDU_HEADER = "94a40800042000";
    private static final String READRECORD_APDU_HEADER = "94b20";
    // "OK" status word sent in response to SELECT AID command (0x9000)
    private static final byte[] SELECT_OK_SW = {(byte) 0x90, (byte) 0x00};

    // XSCP log APDU flow for Montreal OPUS cards (1TIC.ICA applet)
    // From STM home reload tool (tlb.opusenligne.ca, 2020)
    private static final byte[] SELECT_1TIC_ICA = {
        (byte)0x00, (byte)0xA4, (byte)0x04, (byte)0x00,
        (byte)0x08, 0x31, 0x54, 0x49, 0x43, 0x2E, 0x49, 0x43, 0x41
    };
    private static final byte[] GET_RESPONSE = {(byte)0x00, (byte)0xC0, (byte)0x00, (byte)0x00, (byte)0x00};
    // Montreal file IDs from log: 0x3C1D, 0x441D, 0x4C1D, 0x541D, 0x5C1D, 0x641D, 0x6C1D, 0x741D, 0x7C1D, 0x841D, 0x8C1D
    private static final int[] MTL_CONTRACT_FILES = {0x3C, 0x44, 0x4C, 0x54, 0x5C, 0x64, 0x6C, 0x74, 0x7C, 0x84, 0x8C};

    // Weak reference to prevent retain loop. mAccountCallback is responsible for exiting
    // foreground mode before it becomes invalid (e.g. during onPause() or onStop()).
    private WeakReference<AccountCallback> mAccountCallback;

    public interface AccountCallback {
        void onAccountReceived(Card myCard);
    }

    public CardReader(AccountCallback accountCallback) {
        mAccountCallback = new WeakReference<AccountCallback>(accountCallback);
    }

    public static int byteArrayToInt(byte[] b)
    {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int shift = (4 - 1 - i) * 8;
            value += (b[i] & 0x000000FF) << shift;
        }
        return value;
    }
    /**
     * Callback when a new tag is discovered by the system.
     *
     * <p>Communication with the card should take place here.
     *
     * @param tag Discovered tag
     */
    @Override
    public void onTagDiscovered(Tag tag) {
        Log.v(TAG, "New tag discovered");
        // Android's Host-based Card Emulation (HCE) feature implements the ISO-DEP (ISO 14443-4)
        // protocol.
        //
        // In order to communicate with a device using HCE, the discovered tag should be processed
        // using the IsoDep class.
        String sIsoDep = IsoDep.class.getName();
        String sNfcB = NfcB.class.getName();
        String sMifare = MifareUltralight.class.getName();
        String sNfcA = NfcA.class.getName();
        if (tag.toString().contains(sMifare) || tag.toString().contains(sNfcA)) {
            MifareUltralight mifareCard = MifareUltralight.get(tag);
            if (mifareCard != null) {
                try {
                    Log.i(TAG, "Occa detected");
                    mifareCard.connect();
                    byte[] page0to3 = mifareCard.readPages(0);
                    byte[] page4to7 = mifareCard.readPages(4);
                    byte[] page8to11 = mifareCard.readPages(8);
                    byte[] page12to15 = mifareCard.readPages(12);

                    byte[][] cardData = new byte[][]{
                            page0to3,
                            page4to7,
                            page8to11,
                            page12to15};

                    Mifare mainCard = new Mifare(cardData);

                    mAccountCallback.get().onAccountReceived(mainCard.getCard());

                } catch (IOException e) {
                    Log.e(TAG, "Error communicating with card: " + e.toString());
                }
            }
        } else if (tag.toString().contains(sNfcB) || tag.toString().contains(sIsoDep)) {
            IsoDep OpusCard = IsoDep.get(tag);
            if (OpusCard != null) {
                Log.i(TAG, "Opus detected");

                byte[][] dataTransit = new byte[3][];
                byte[][] dataSubscription = new byte[4][];
                byte[][] dataTicket = new byte[4][];

                try {
                    OpusCard.connect();

                    // Try Montreal-style SELECT first (1TIC.ICA applet),
                    // fall back to direct file selection if it fails
                    byte[] selectResponse;
                    try {
                        selectResponse = OpusCard.transceive(SELECT_1TIC_ICA);
                        if (selectResponse.length == 2 && selectResponse[0] == (byte)0x90 && selectResponse[1] == 0x00) {
                            Log.i(TAG, "Montreal OPUS detected (1TIC.ICA)");
                            // Get response data from SELECT
                            OpusCard.transceive(GET_RESPONSE);
                            // Use Montreal file IDs for contract reads
                        } else {
                            Log.i(TAG, "1TIC.ICA not found, trying direct file selection");
                            selectResponse = OpusCard.transceive(Utils.HexStringToByteArray("94A4000002000219"));
                        }
                    } catch (Exception e) {
                        Log.i(TAG, "1TIC.ICA SELECT failed, trying direct file selection");
                        selectResponse = OpusCard.transceive(Utils.HexStringToByteArray("94A4000002000219"));
                    }

                    // If direct file selection failed too, try original flow
                    if (selectResponse.length == 2 && selectResponse[1] == (byte)0x6E) {
                        Log.i(TAG, "Direct SELECT failed, using original flow");
                        OpusCard.transceive(Utils.HexStringToByteArray("94A4000002000219"));
                    }

                    // ICC (Card ID)
                    byte[] dataID = OpusCard.transceive(Utils.HexStringToByteArray("94B201041D"));

                    // Enviroment (Expiration)
                    OpusCard.transceive(getSelectApdu("2001"));
                    byte[] dataExp = OpusCard.transceive(getReadApdu(1));

                    // Transit
                    OpusCard.transceive(getSelectApdu("2010"));
                    for(int i = 0; i < 3; i++){
                        dataTransit[i] = OpusCard.transceive(getReadApdu(i+1));
                    }
                    //Fake Transit, uncomment next block
//                    dataTransit[0] = Utils.HexStringToByteArray("70AA3500000CF000282040014877B85515C622");
//                    dataTransit[1] = Utils.HexStringToByteArray("70AA2B80000CF0002835F0016077B85515C620");
//                    dataTransit[2] = Utils.HexStringToByteArray("70923380000CF00028B338013877B849150622");

                    // Subscription
                    OpusCard.transceive(getSelectApdu("2020"));
                    for(int i = 0; i < 4; i++){
                        dataSubscription[i] = OpusCard.transceive(getReadApdu(i+1));
                    }
                    //Fake Subs, uncomment next block
//                    dataSubscription[0] = Utils.HexStringToByteArray("1E0A092B80000007F48DB66826E0");
//                    dataSubscription[1] = Utils.HexStringToByteArray("1E0A0043B5F8D8D7F48DAF966A00");
//                    dataSubscription[2] = Utils.HexStringToByteArray("1E0A0043B2A4CB87F48D94E6D600");
//                    dataSubscription[3] = Utils.HexStringToByteArray("1E0A079BB2E6CBD7F48D9736E800");

                    // Ticket
                    OpusCard.transceive(getSelectApdu("202A"));
                    dataTicket[0] = OpusCard.transceive(getReadApdu(1));
                    OpusCard.transceive(getSelectApdu("202B"));
                    dataTicket[1] = OpusCard.transceive(getReadApdu(1));
                    OpusCard.transceive(getSelectApdu("202C"));
                    dataTicket[2] = OpusCard.transceive(getReadApdu(1));
                    OpusCard.transceive(getSelectApdu("202D"));
                    dataTicket[3] = OpusCard.transceive(getReadApdu(1));

                    Opus mainCard = new Opus(dataID, dataExp, dataTransit, dataSubscription, dataTicket);
                    Card card = mainCard.getCard();
                    card.addExtraData("_enumerate", new byte[]{(byte)0xFF}); // marker that enumeration ran

                    // === Full OPUS file enumeration ===
                    // Probes ~400 file IDs and stores raw data from each found file.
                    // Total ~8 seconds. The user must hold the card steady.

                    java.util.List<Integer> foundFiles = new java.util.ArrayList<>();

                    // 1. Standard Calypso files: 0x2000-0x207F (128 files, ~2.5s)
                    for (int fid = 0x2000; fid <= 0x207F; fid++) {
                        byte[] data = probeFile(OpusCard, fid);
                        if (data != null) {
                            foundFiles.add(fid);
                            if (data.length > 0) card.addExtraData("f" + String.format("%04X", fid), data);
                        }
                    }

                    // 2. System / directory files: 0x0000-0x00FF
                    for (int fid = 0x0000; fid <= 0x00FF; fid++) {
                        byte[] data = probeFile(OpusCard, fid);
                        if (data != null) {
                            foundFiles.add(fid);
                            if (data.length > 0) card.addExtraData("f" + String.format("%04X", fid), data);
                        }
                    }

                    // 3. Extended range: 0x1000-0x107F
                    for (int fid = 0x1000; fid <= 0x107F; fid++) {
                        byte[] data = probeFile(OpusCard, fid);
                        if (data != null) {
                            foundFiles.add(fid);
                            if (data.length > 0) card.addExtraData("f" + String.format("%04X", fid), data);
                        }
                    }

                    // 4. Contract files: 0x3C1D, 0x441D... 0x8C1D (step 0x0800)
                    for (int fi = 0; fi < MTL_CONTRACT_FILES.length; fi++) {
                        int fid = MTL_CONTRACT_FILES[fi] * 256 + 0x1D;
                        byte[] data = probeFile(OpusCard, fid);
                        if (data != null) {
                            foundFiles.add(fid);
                            if (data.length > 0) card.addExtraData("f" + String.format("%04X", fid), data);
                        }
                    }

                    // 5. Extra contract slots between known ones (step 0x0100)
                    for (int fi = 0; fi < MTL_CONTRACT_FILES.length - 1; fi++) {
                        int start = MTL_CONTRACT_FILES[fi] * 256 + 0x1D;
                        int end = MTL_CONTRACT_FILES[fi + 1] * 256 + 0x1D;
                        for (int fid = start + 0x100; fid < end; fid += 0x100) {
                            byte[] data = probeFile(OpusCard, fid);
                            if (data != null) {
                                foundFiles.add(fid);
                                if (data.length > 0) card.addExtraData("f" + String.format("%04X", fid), data);
                            }
                        }
                    }

                    // 6. Try reading directory info (file 0x0000 as transparent binary)
                    if (foundFiles.contains(0x0000)) {
                        try {
                            OpusCard.transceive(Utils.HexStringToByteArray("00A40000020000"));
                            byte[] dir = OpusCard.transceive(Utils.HexStringToByteArray("00B0000000"));
                            if (dir != null && dir.length > 2 && dir[dir.length-2] == (byte)0x90) {
                                byte[] dirData = new byte[dir.length - 2];
                                System.arraycopy(dir, 0, dirData, 0, dirData.length);
                                card.addExtraData("_directory", dirData);
                            }
                        } catch (Exception ignored) {}
                    }

                    // Store list of found files
                    StringBuilder sb = new StringBuilder();
                    for (int f : foundFiles) {
                        if (sb.length() > 0) sb.append(",");
                        sb.append(String.format("%04X", f));
                    }
                    card.addExtraData("_found_files", sb.toString().getBytes());

                    mAccountCallback.get().onAccountReceived(card);


                }
                catch (IOException e) {
                    Log.e(TAG, "Error communicating with card: " + e.toString());
                }

            }
        }
    }

    byte[] getReadApdu(int i){ // i must be between 1 and 9
        String readString = READRECORD_APDU_HEADER + i + "0400";
        return Utils.HexStringToByteArray(readString);
    }

    byte[] getSelectApdu(String HexEnd){ // HexEnd must be 4 characters (Ex. 20A0)
        String selectString = SELECT_APDU_HEADER + HexEnd;
        return Utils.HexStringToByteArray(selectString);
    }

    // Probe a file by 2-byte ID: SELECT then READ first record.
    // Returns the raw record data (minus status word) or null if file not found.
    private byte[] probeFile(IsoDep iso, int fileId) {
        try {
            byte[] selectCmd = new byte[]{
                (byte)0x00, (byte)0xA4, (byte)0x00, (byte)0x00,
                (byte)0x02, (byte)(fileId >> 8), (byte)(fileId & 0xFF)
            };
            byte[] resp = iso.transceive(selectCmd);
            if (resp != null && resp.length >= 2 &&
                resp[resp.length - 2] == (byte)0x90 && resp[resp.length - 1] == 0x00) {
                // SELECT succeeded — try READ RECORD 1
                byte[] record = iso.transceive(Utils.HexStringToByteArray("00B2010400"));
                if (record != null && record.length >= 4 &&
                    record[record.length - 2] == (byte)0x90 && record[record.length - 1] == 0x00) {
                    byte[] data = new byte[record.length - 2];
                    System.arraycopy(record, 0, data, 0, data.length);
                    return data;
                }
                // File exists but READ failed — return empty marker
                return new byte[0];
            }
        } catch (Exception e) {
            // File not found or communication error
        }
        return null;
    }

}

/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.telephony;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import android.content.Context;
import android.os.SystemProperties;
import android.os.Build;
import android.text.TextUtils;
import android.content.ContentResolver;
import android.database.Cursor;
import android.database.SQLException;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.telephony.Rlog;
import com.android.internal.telephony.HbpcdLookup.MccIdd;
import com.android.internal.telephony.HbpcdLookup.MccLookup;


 /**
 * This class implements handle the MO SMS target address before sending.
 * This is special for VZW requirement. Follow the specificaitons of assisted dialing
 * of MO SMS while traveling on VZW CDMA, international CDMA or GSM markets.
 * {@hide}
 */
public class SmsNumberUtils {
    private static final String TAG = "SmsNumberUtils";
    private static final boolean DBG = Build.IS_DEBUGGABLE;

    private static final String PLUS_SIGN = "+";

    private static final int NANP_SHORT_LENGTH = 7;
    private static final int NANP_MEDIUM_LENGTH = 10;
    private static final int NANP_LONG_LENGTH = 11;

    private static final int NANP_CC = 1;
    private static final String NANP_NDD = "1";
    private static final String NANP_IDD = "011";

    private static final int MIN_COUNTRY_AREA_LOCAL_LENGTH = 10;

    private static final int GSM_UMTS_NETWORK = 0;
    private static final int CDMA_HOME_NETWORK = 1;
    private static final int CDMA_ROAMING_NETWORK = 2;

    private static final int NP_NONE = 0;
    private static final int NP_NANP_BEGIN = 1;

    /* <Phone Number>, <NXX>-<XXXX> N[2-9] */
    private static final int NP_NANP_LOCAL = NP_NANP_BEGIN;

    /* <Area_code>-<Phone Number>, <NXX>-<NXX>-<XXXX> N[2-9] */
    private static final int NP_NANP_AREA_LOCAL = NP_NANP_BEGIN + 1;

    /* <1>-<Area_code>-<Phone Number>, 1-<NXX>-<NXX>-<XXXX> N[2-9] */
    private static final int NP_NANP_NDD_AREA_LOCAL = NP_NANP_BEGIN + 2;

    /* <+><U.S.Country_code><Area_code><Phone Number>, +1-<NXX>-<NXX>-<XXXX> N[2-9] */
    private static final int NP_NANP_NBPCD_CC_AREA_LOCAL = NP_NANP_BEGIN + 3;

    /* <Local_IDD><Country_code><Area_code><Phone Number>, 001-1-<NXX>-<NXX>-<XXXX> N[2-9] */
    private static final int NP_NANP_LOCALIDD_CC_AREA_LOCAL = NP_NANP_BEGIN + 4;

    /* <+><Home_IDD><Country_code><Area_code><Phone Number>, +011-1-<NXX>-<NXX>-<XXXX> N[2-9] */
    private static final int NP_NANP_NBPCD_HOMEIDD_CC_AREA_LOCAL = NP_NANP_BEGIN + 5;

    private static final int NP_INTERNATIONAL_BEGIN = 100;
    /* <+>-<Home_IDD>-<Country_code>-<Area_code>-<Phone Number>, +011-86-25-86281234 */
    private static final int NP_NBPCD_HOMEIDD_CC_AREA_LOCAL = NP_INTERNATIONAL_BEGIN;

    /* <Home_IDD>-<Country_code>-<Area_code>-<Phone Number>, 011-86-25-86281234 */
    private static final int NP_HOMEIDD_CC_AREA_LOCAL = NP_INTERNATIONAL_BEGIN + 1;

    /* <NBPCD>-<Country_code>-<Area_code>-<Phone Number>, +1-86-25-86281234 */
    private static final int NP_NBPCD_CC_AREA_LOCAL = NP_INTERNATIONAL_BEGIN + 2;

    /* <Local_IDD>-<Country_code>-<Area_code>-<Phone Number>, 00-86-25-86281234 */
    private static final int NP_LOCALIDD_CC_AREA_LOCAL = NP_INTERNATIONAL_BEGIN + 3;

    /* <Country_code>-<Area_code>-<Phone Number>, 86-25-86281234*/
    private static final int NP_CC_AREA_LOCAL = NP_INTERNATIONAL_BEGIN + 4;

    private static int[] ALL_COUNTRY_CODES = null;
    private static int MAX_COUNTRY_CODES_LENGTH;
    private static HashMap<String, ArrayList<String>> IDDS_MAPS =
            new HashMap<String, ArrayList<String>>();

    private static class NumberEntry {
        public String number;
        public String IDD;
        public int countryCode;
        public NumberEntry(String number) {
            this.number = number;
        }
    }

    /* Breaks the given number down and formats it according to the rules
     * for different number plans and differnt network.
     *
     * @param number destionation number which need to be format
     * @param activeMcc current network's mcc
     * @param networkType current network type
     *
     * @return the number after fromatting.
     */
    private static String formatNumber(Context context, String number,
                               String activeMcc,
                               int networkType) {
        if (number == null ) {
            throw new IllegalArgumentException("number is null");
        }

        if (activeMcc == null || activeMcc.trim().length() == 0) {
            throw new IllegalArgumentException("activeMcc is null or empty!");
        }

        String networkPortionNumber = PhoneNumberUtils.extractNetworkPortion(number);
        if (networkPortionNumber == null || networkPortionNumber.length() == 0) {
            throw new IllegalArgumentException("Number is invalid!");
        }

        NumberEntry numberEntry = new NumberEntry(networkPortionNumber);
        ArrayList<String> allIDDs = getAllIDDs(context, activeMcc);

        // First check whether the number is a NANP number.
        int nanpState = checkNANP(numberEntry, allIDDs);
        if (DBG) Rlog.d(TAG, "NANP type: " + getNumberPlanType(nanpState));

        if ((nanpState == NP_NANP_LOCAL)
            || (nanpState == NP_NANP_AREA_LOCAL)
            || (nanpState == NP_NANP_NDD_AREA_LOCAL)) {
            return networkPortionNumber;
        } else if (nanpState == NP_NANP_NBPCD_CC_AREA_LOCAL) {
            if (networkType == CDMA_HOME_NETWORK
                    || networkType == CDMA_ROAMING_NETWORK) {
                // Remove "+"
                return networkPortionNumber.substring(1);
            } else {
                return networkPortionNumber;
            }
        } else if (nanpState == NP_NANP_LOCALIDD_CC_AREA_LOCAL) {
            if (networkType == CDMA_HOME_NETWORK) {
                return networkPortionNumber;
            } else if (networkType == GSM_UMTS_NETWORK) {
                // Remove the local IDD and replace with "+"
                int iddLength  =  numberEntry.IDD != null ? numberEntry.IDD.length() : 0;
                return PLUS_SIGN + networkPortionNumber.substring(iddLength);
            } else if (networkType == CDMA_ROAMING_NETWORK) {
                // Remove the local IDD
                int iddLength  =  numberEntry.IDD != null ? numberEntry.IDD.length() : 0;
                return  networkPortionNumber.substring(iddLength);
            }
        }

        int internationalState = checkInternationalNumberPlan(context, numberEntry, allIDDs,
                NANP_IDD);
        if (DBG) Rlog.d(TAG, "International type: " + getNumberPlanType(internationalState));
        String returnNumber = null;

        switch (internationalState) {
            case NP_NBPCD_HOMEIDD_CC_AREA_LOCAL:
                if (networkType == GSM_UMTS_NETWORK) {
                    // Remove "+"
                    returnNumber = networkPortionNumber.substring(1);
                }
                break;

            case NP_NBPCD_CC_AREA_LOCAL:
                // Replace "+" with "011"
                returnNumber = NANP_IDD + networkPortionNumber.substring(1);
                break;

            case NP_LOCALIDD_CC_AREA_LOCAL:
                if (networkType == GSM_UMTS_NETWORK || networkType == CDMA_ROAMING_NETWORK) {
                    int iddLength  =  numberEntry.IDD != null ? numberEntry.IDD.length() : 0;
                    // Replace <Local IDD> to <Home IDD>("011")
                    returnNumber = NANP_IDD + networkPortionNumber.substring(iddLength);
                }
                break;

            case NP_CC_AREA_LOCAL:
                int countryCode = numberEntry.countryCode;

                if (!inExceptionListForNpCcAreaLocal(numberEntry)
                    && networkPortionNumber.length() >= 11 && countryCode != NANP_CC) {
                    // Add "011"
                    returnNumber = NANP_IDD + networkPortionNumber;
                }
                break;

            case NP_HOMEIDD_CC_AREA_LOCAL:
                returnNumber = networkPortionNumber;
                break;

            default:
                // Replace "+" with 011 in CDMA network if the number's country
                // code is not in the HbpcdLookup database.
                if (networkPortionNumber.startsWith(PLUS_SIGN)
                    && (networkType == CDMA_HOME_NETWORK || networkType == CDMA_ROAMING_NETWORK)) {
                    if (networkPortionNumber.startsWith(PLUS_SIGN + NANP_IDD)) {
                        // Only remove "+"
                        returnNumber = networkPortionNumber.substring(1);
                    } else {
                        // Replace "+" with "011"
                        returnNumber = NANP_IDD + networkPortionNumber.substring(1);
                    }
                }
        }

        if (returnNumber == null) {
            returnNumber = networkPortionNumber;
        }
        return returnNumber;
    }

    /* Query International direct dialing from HbpcdLookup.db
     * for specified country code
     *
     * @param mcc current network's country code
     *
     * @return the IDD array list.
     */
    private static ArrayList<String> getAllIDDs(Context context, String mcc) {
        ArrayList<String> allIDDs = IDDS_MAPS.get(mcc);
        if (allIDDs != null) {
            return allIDDs;
        } else {
            allIDDs = new ArrayList<String>();
        }

        String projection[] = {MccIdd.IDD, MccIdd.MCC};
        String where = null;

        // if mcc is null         : return all rows
        // if mcc is empty-string : return those rows whose mcc is emptry-string
        String[] selectionArgs = null;
        if (mcc != null) {
            where = MccIdd.MCC + "=?";
            selectionArgs = new String[] {mcc};
        }

        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(MccIdd.CONTENT_URI, projection,
                    where, selectionArgs, null);
            if (cursor.getCount() > 0) {
                while (cursor.moveToNext()) {
                    String idd = cursor.getString(0);
                    if (!allIDDs.contains(idd)) {
                        allIDDs.add(idd);
                    }
                }
            }
        } catch (SQLException e) {
            Rlog.e(TAG, "Can't access HbpcdLookup database", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        IDDS_MAPS.put(mcc, allIDDs);

        if (DBG) Rlog.d(TAG, "MCC = " + mcc + ", all IDDs = " + allIDDs);
        return allIDDs;
    }


    /* Verify if the the destination number is a NANP number
     *
     * @param numberEntry including number and IDD array
     * @param allIDDs the IDD array list of the current network's country code
     *
     * @return the number plan type related NANP
     */
    private static int checkNANP(NumberEntry numberEntry, ArrayList<String> allIDDs) {
        boolean isNANP = false;
        String number = numberEntry.number;

        if (number.length() == NANP_SHORT_LENGTH) {
            // 7 digits - Seven digit phone numbers
            char firstChar = number.charAt(0);
            if (firstChar >= '2' && firstChar <= '9') {
                isNANP = true;
                for (int i=1; i< NANP_SHORT_LENGTH; i++ ) {
                    char c= number.charAt(i);
                    if (!PhoneNumberUtils.isISODigit(c)) {
                        isNANP = false;
                        break;
                    }
                }
            }
            if (isNANP) {
                return NP_NANP_LOCAL;
            }
        } else if (number.length() == NANP_MEDIUM_LENGTH) {
            // 10 digits - Three digit area code followed by seven digit phone numbers/
            if (isNANP(number)) {
                return NP_NANP_AREA_LOCAL;
            }
        } else if (number.length() == NANP_LONG_LENGTH) {
            // 11 digits - One digit U.S. NDD(National Direct Dial) prefix '1',
            // followed by three digit area code and seven digit phone numbers
            if (isNANP(number)) {
                return NP_NANP_NDD_AREA_LOCAL;
            }
        } else if (number.startsWith(PLUS_SIGN)) {
            number = number.substring(1);
            if (number.length() == NANP_LONG_LENGTH) {
                // '+' and 11 digits -'+', followed by NANP CC prefix '1' followed by
                // three digit area code and seven digit phone numbers
                if (isNANP(number)) {
                    return NP_NANP_NBPCD_CC_AREA_LOCAL;
                }
            } else if (number.startsWith(NANP_IDD) && number.length() == NANP_LONG_LENGTH + 3) {
                // '+' and 14 digits -'+', followed by NANP IDD "011" followed by NANP CC
                // prefix '1' followed by three digit area code and seven digit phone numbers
                number = number.substring(3);
                if (isNANP(number)) {
                    return NP_NANP_NBPCD_HOMEIDD_CC_AREA_LOCAL;
                }
            }
        } else {
            // Check whether it's NP_NANP_LOCALIDD_CC_AREA_LOCAL
            for (String idd : allIDDs) {
                if (number.startsWith(idd)) {
                    String number2 = number.substring(idd.length());
                    if(number2 !=null && number2.startsWith(String.valueOf(NANP_CC))){
                        if (isNANP(number2)) {
                            numberEntry.IDD = idd;
                            return NP_NANP_LOCALIDD_CC_AREA_LOCAL;
                        }
                    }
                }
            }
        }

        return NP_NONE;
    }

    private static boolean isNANP(String number) {
        if (number.length() == NANP_MEDIUM_LENGTH
            || (number.length() == NANP_LONG_LENGTH  && number.startsWith(NANP_NDD))) {
            if (number.length() == NANP_LONG_LENGTH) {
                number = number.substring(1);
            }
            return (PhoneNumberUtils.isNanp(number));
        }
        return false;
    }

    /* Verify if the the destination number is an internal number
     *
     * @param numberEntry including number and IDD array
     * @param allIDDs the IDD array list of the current network's country code
     *
     * @return the number plan type related international number
     */
    private static int checkInternationalNumberPlan(Context context, NumberEntry numberEntry,
            ArrayList<String> allIDDs,String homeIDD) {
        String number = numberEntry.number;
        int countryCode = -1;

        if (number.startsWith(PLUS_SIGN)) {
            // +xxxxxxxxxx
            String numberNoNBPCD = number.substring(1);
            if (numberNoNBPCD.startsWith(homeIDD)) {
                // +011xxxxxxxx
                String numberCountryAreaLocal = numberNoNBPCD.substring(homeIDD.length());
                if ((countryCode = getCountryCode(context, numberCountryAreaLocal)) > 0) {
                    numberEntry.countryCode = countryCode;
                    return NP_NBPCD_HOMEIDD_CC_AREA_LOCAL;
                }
            } else if ((countryCode = getCountryCode(context, numberNoNBPCD)) > 0) {
                numberEntry.countryCode = countryCode;
                return NP_NBPCD_CC_AREA_LOCAL;
            }

        } else if (number.startsWith(homeIDD)) {
            // 011xxxxxxxxx
            String numberCountryAreaLocal = number.substring(homeIDD.length());
            if ((countryCode = getCountryCode(context, numberCountryAreaLocal)) > 0) {
                numberEntry.countryCode = countryCode;
                return NP_HOMEIDD_CC_AREA_LOCAL;
            }
        } else {
            for (String exitCode : allIDDs) {
                if (number.startsWith(exitCode)) {
                    String numberNoIDD = number.substring(exitCode.length());
                    if ((countryCode = getCountryCode(context, numberNoIDD)) > 0) {
                        numberEntry.countryCode = countryCode;
                        numberEntry.IDD = exitCode;
                        return NP_LOCALIDD_CC_AREA_LOCAL;
                    }
                }
            }

            if (!number.startsWith("0") && (countryCode = getCountryCode(context, number)) > 0) {
                numberEntry.countryCode = countryCode;
                return NP_CC_AREA_LOCAL;
            }
        }
        return NP_NONE;
    }

    /**
     *  Returns the country code from the given number.
     */
    private static int getCountryCode(Context context, String number) {
        int countryCode = -1;
        if (number.length() >= MIN_COUNTRY_AREA_LOCAL_LENGTH) {
            // Check Country code
            int[] allCCs = getAllCountryCodes(context);
            if (allCCs == null) {
                return countryCode;
            }

            int[] ccArray = new int[MAX_COUNTRY_CODES_LENGTH];
            for (int i = 0; i < MAX_COUNTRY_CODES_LENGTH; i ++) {
                ccArray[i] = Integer.valueOf(number.substring(0, i+1));
            }

            for (int i = 0; i < allCCs.length; i ++) {
                int tempCC = allCCs[i];
                for (int j = 0; j < MAX_COUNTRY_CODES_LENGTH; j ++) {
                    if (tempCC == ccArray[j]) {
                        if (DBG) Rlog.d(TAG, "Country code = " + tempCC);
                        return tempCC;
                    }
                }
            }
        }

        return countryCode;
    }

    /**
     *  Gets all country Codes information with given MCC.
     */
    private static int[] getAllCountryCodes(Context context) {
        if (ALL_COUNTRY_CODES != null) {
            return ALL_COUNTRY_CODES;
        }

        Cursor cursor = null;
        try {
            String projection[] = {MccLookup.COUNTRY_CODE};
            cursor = context.getContentResolver().query(MccLookup.CONTENT_URI,
                    projection, null, null, null);

            if (cursor.getCount() > 0) {
                ALL_COUNTRY_CODES = new int[cursor.getCount()];
                int i = 0;
                while (cursor.moveToNext()) {
                    int countryCode = cursor.getInt(0);
                    ALL_COUNTRY_CODES[i++] = countryCode;
                    int length = String.valueOf(countryCode).trim().length();
                    if (length > MAX_COUNTRY_CODES_LENGTH) {
                        MAX_COUNTRY_CODES_LENGTH = length;
                    }
                }
            }
        } catch (SQLException e) {
            Rlog.e(TAG, "Can't access HbpcdLookup database", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return ALL_COUNTRY_CODES;
    }

    private static boolean inExceptionListForNpCcAreaLocal(NumberEntry numberEntry) {
        int countryCode = numberEntry.countryCode;
        boolean result = (numberEntry.number.length() == 12
                          && (countryCode == 7 || countryCode == 20
                              || countryCode == 65 || countryCode == 90));
        return result;
    }

    private static String getNumberPlanType(int state) {
        String numberPlanType = "Number Plan type (" + state + "): ";

        if (state == NP_NANP_LOCAL) {
            numberPlanType = "NP_NANP_LOCAL";
        } else if (state == NP_NANP_AREA_LOCAL) {
            numberPlanType = "NP_NANP_AREA_LOCAL";
        } else if (state  == NP_NANP_NDD_AREA_LOCAL) {
            numberPlanType = "NP_NANP_NDD_AREA_LOCAL";
        } else if (state == NP_NANP_NBPCD_CC_AREA_LOCAL) {
            numberPlanType = "NP_NANP_NBPCD_CC_AREA_LOCAL";
        } else if (state == NP_NANP_LOCALIDD_CC_AREA_LOCAL) {
            numberPlanType = "NP_NANP_LOCALIDD_CC_AREA_LOCAL";
        } else if (state == NP_NANP_NBPCD_HOMEIDD_CC_AREA_LOCAL) {
            numberPlanType = "NP_NANP_NBPCD_HOMEIDD_CC_AREA_LOCAL";
        } else if (state == NP_NBPCD_HOMEIDD_CC_AREA_LOCAL) {
            numberPlanType = "NP_NBPCD_IDD_CC_AREA_LOCAL";
        } else if (state == NP_HOMEIDD_CC_AREA_LOCAL) {
            numberPlanType = "NP_IDD_CC_AREA_LOCAL";
        } else if (state == NP_NBPCD_CC_AREA_LOCAL) {
            numberPlanType = "NP_NBPCD_CC_AREA_LOCAL";
        } else if (state == NP_LOCALIDD_CC_AREA_LOCAL) {
            numberPlanType = "NP_IDD_CC_AREA_LOCAL";
        } else if (state == NP_CC_AREA_LOCAL) {
            numberPlanType = "NP_CC_AREA_LOCAL";
        } else {
            numberPlanType = "Unknown type";
        }
        return numberPlanType;
    }

    /**
     *  Filter the destination number if using VZW sim card.
     */
    public static String filterDestAddr(PhoneBase phoneBase, String destAddr) {
        if (DBG) Rlog.d(TAG, "enter filterDestAddr. destAddr=\"" + destAddr + "\"" );

        if (destAddr == null || !PhoneNumberUtils.isGlobalPhoneNumber(destAddr)) {
            Rlog.w(TAG, "destAddr" + destAddr + " is not a global phone number!");
            return destAddr;
        }

        final String networkOperator = TelephonyManager.getDefault().getNetworkOperator();
        String result = null;

        if (needToConvert(phoneBase)) {
            final int networkType = getNetworkType(phoneBase);
            if (networkType != -1) {
                String networkMcc = networkOperator.substring(0, 3);
                if (networkMcc != null && networkMcc.trim().length() > 0) {
                    result = formatNumber(phoneBase.getContext(), destAddr, networkMcc, networkType);
                }
            }
        }

        if (DBG) Rlog.d(TAG, "leave filterDestAddr, new destAddr=\"" + result + "\"" );
        return result != null ? result : destAddr;
    }

    /**
     * Returns the current network type
     */
    private static int getNetworkType(PhoneBase phoneBase) {
        int networkType = -1;
        int phoneType = TelephonyManager.getDefault().getPhoneType();

        if (phoneType == TelephonyManager.PHONE_TYPE_GSM) {
            networkType = GSM_UMTS_NETWORK;
        } else if (phoneType == TelephonyManager.PHONE_TYPE_CDMA) {
            if (isInternationalRoaming(phoneBase)) {
                networkType = CDMA_ROAMING_NETWORK;
            } else {
                networkType = CDMA_HOME_NETWORK;
            }
        } else {
            if (DBG) Rlog.w(TAG, "warning! unknown mPhoneType value=" + phoneType);
        }

        return networkType;
    }

    private static boolean isInternationalRoaming(PhoneBase phoneBase) {
        String operatorIsoCountry = phoneBase.getSystemProperty(
                TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY, "");
        String simIsoCountry = phoneBase.getSystemProperty(
                TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY, "");
        boolean internationalRoaming = !TextUtils.isEmpty(operatorIsoCountry)
                && !TextUtils.isEmpty(simIsoCountry)
                && !simIsoCountry.equals(operatorIsoCountry);
        if (internationalRoaming) {
            if ("us".equals(simIsoCountry)) {
                internationalRoaming = !"vi".equals(operatorIsoCountry);
            } else if ("vi".equals(simIsoCountry)) {
                internationalRoaming = !"us".equals(operatorIsoCountry);
            }
        }
        return internationalRoaming;
    }

    private static boolean needToConvert(PhoneBase phoneBase) {
        boolean bNeedToConvert  = false;
        String[] listArray = phoneBase.getContext().getResources()
                .getStringArray(com.android.internal.R.array
                .config_sms_convert_destination_number_support);
        if (listArray != null && listArray.length > 0) {
            for (int i=0; i<listArray.length; i++) {
                if (!TextUtils.isEmpty(listArray[i])) {
                    String[] needToConvertArray = listArray[i].split(";");
                    if (needToConvertArray != null && needToConvertArray.length > 0) {
                        if (needToConvertArray.length == 1) {
                            bNeedToConvert = "true".equalsIgnoreCase(needToConvertArray[0]);
                        } else if (needToConvertArray.length == 2 &&
                                !TextUtils.isEmpty(needToConvertArray[1]) &&
                                compareGid1(phoneBase, needToConvertArray[1])) {
                            bNeedToConvert = "true".equalsIgnoreCase(needToConvertArray[0]);
                            break;
                        }
                    }
                }
            }
        }
        return bNeedToConvert;
    }

    private static boolean compareGid1(PhoneBase phoneBase, String serviceGid1) {
        String gid1 = phoneBase.getGroupIdLevel1();
        boolean ret = true;

        if (TextUtils.isEmpty(serviceGid1)) {
            if (DBG) Rlog.d(TAG, "compareGid1 serviceGid is empty, return " + ret);
            return ret;
        }

        int gid_length = serviceGid1.length();
        // Check if gid1 match service GID1
        if (!((gid1 != null) && (gid1.length() >= gid_length) &&
                gid1.substring(0, gid_length).equalsIgnoreCase(serviceGid1))) {
            if (DBG) Rlog.d(TAG, " gid1 " + gid1 + " serviceGid1 " + serviceGid1);
            ret = false;
        }
        if (DBG) Rlog.d(TAG, "compareGid1 is " + (ret?"Same":"Different"));
        return ret;
    }
}

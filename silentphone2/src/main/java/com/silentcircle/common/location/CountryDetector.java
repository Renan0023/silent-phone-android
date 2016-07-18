/*
Copyright (C) 2016, Silent Circle, LLC.  All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Any redistribution, use, or modification is done solely for personal
      benefit and not for any commercial purpose or for monetary gain
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the name Silent Circle nor the
      names of its contributors may be used to endorse or promote products
      derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL SILENT CIRCLE, LLC BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package com.silentcircle.common.location;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.preference.PreferenceManager;
import android.support.v4.content.ContextCompat;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.silentcircle.common.testing.NeededForTesting;

import java.util.Locale;

/**
 * This class is used to detect the country where the user is. It is a simplified version of the
 * country detector service in the framework. The sources of country location are queried in the
 * following order of reliability:
 * <ul>
 * <li>Mobile network</li>
 * <li>Location manager</li>
 * <li>SIM's country</li>
 * <li>User's default locale</li>
 * </ul>
 *
 * As far as possible this class tries to replicate the behavior of the system's country detector
 * service:
 * 1) Order in priority of sources of country location
 * 2) Mobile network information provided by CDMA phones is ignored
 * 3) Location information is updated every 12 hours (instead of 24 hours in the system)
 * 4) Location updates only uses the {@link LocationManager#PASSIVE_PROVIDER} to avoid active use
 *    of the GPS
 * 5) If a location is successfully obtained and geocoded, we never fall back to use of the
 *    SIM's country (for the system, the fallback never happens without a reboot)
 * 6) Location is not used if the device does not implement a {@link Geocoder}
*/
public class CountryDetector {
    private static final String TAG = "CountryDetector";

    public static final String KEY_PREFERENCE_TIME_UPDATED = "preference_time_updated";
    public static final String KEY_PREFERENCE_CURRENT_COUNTRY = "preference_current_country";

    private static CountryDetector sInstance;

    private final TelephonyManager mTelephonyManager;
    private final LocationManager mLocationManager;
    private final LocaleProvider mLocaleProvider;

    // Used as a default country code when all the sources of country data have failed in the
    // exceedingly rare event that the device does not have a default locale set for some reason.
    private final String DEFAULT_COUNTRY_ISO = "US";

    // Wait 12 hours between updates
    private static final long TIME_BETWEEN_UPDATES_MS = 1000L * 60 * 60 * 12;

    // Minimum distance before an update is triggered, in meters. We don't need this to be too
    // exact because all we care about is what country the user is in.
    private static final long DISTANCE_BETWEEN_UPDATES_METERS = 5000;

    private final Context mContext;

    /**
     * Class that can be used to return the user's default locale. This is in its own class so that
     * it can be mocked out.
     */
    public static class LocaleProvider {
        public Locale getDefaultLocale() {
            return Locale.getDefault();
        }
    }

    private CountryDetector(Context context) {
        this (context, (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE),
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE),
                new LocaleProvider());
    }

    private CountryDetector(Context context, TelephonyManager telephonyManager,
            LocationManager locationManager, LocaleProvider localeProvider) {
        mTelephonyManager = telephonyManager;
        mLocationManager = locationManager;
        mLocaleProvider = localeProvider;
        mContext = context;

        registerForLocationUpdates(context, mLocationManager);
    }

    public static void registerForLocationUpdates(Context context,
            LocationManager locationManager) {
        if (!Geocoder.isPresent()) {
            // Certain devices do not have an implementation of a geocoder - in that case there is
            // no point trying to get location updates because we cannot retrieve the country based
            // on the location anyway.
            return;
        }
        final Intent activeIntent = new Intent(context, LocationChangedReceiver.class);
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, activeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        if (ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER,
                    TIME_BETWEEN_UPDATES_MS, DISTANCE_BETWEEN_UPDATES_METERS, pendingIntent);
        }
    }

    /**
     * Factory method for {@link CountryDetector} that allows the caller to provide mock objects.
     */
    @NeededForTesting
    public CountryDetector getInstanceForTest(Context context, TelephonyManager telephonyManager,
            LocationManager locationManager, LocaleProvider localeProvider, Geocoder geocoder) {
        return new CountryDetector(context, telephonyManager, locationManager, localeProvider);
    }

    /**
     * Returns the instance of the country detector. {@link #initialize(Context)} must have been
     * called previously.
     *
     * @return the initialized country detector.
     */
    public synchronized static CountryDetector getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new CountryDetector(context.getApplicationContext());
        }
        return sInstance;
    }

    public String getCurrentCountryIso() {
        String result = null;
        if (isNetworkCountryCodeAvailable()) {
            result = getNetworkBasedCountryIso();
        }
        if (TextUtils.isEmpty(result)) {
            result = getLocationBasedCountryIso();
        }
        if (TextUtils.isEmpty(result)) {
            result = getSimBasedCountryIso();
        }
        if (TextUtils.isEmpty(result)) {
            result = getLocaleBasedCountryIso();
        }
        if (TextUtils.isEmpty(result)) {
            result = DEFAULT_COUNTRY_ISO;
        }
        return result.toUpperCase(Locale.US);
    }

    /**
     * @return the country code of the current telephony network the user is connected to.
     */
    private String getNetworkBasedCountryIso() {
        return mTelephonyManager == null ? null :  mTelephonyManager.getNetworkCountryIso();
    }

    /**
     * @return the geocoded country code detected by the {@link LocationManager}.
     */
    private String getLocationBasedCountryIso() {
        if (!Geocoder.isPresent()) {
            return null;
        }
        final SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(mContext);
        return sharedPreferences.getString(KEY_PREFERENCE_CURRENT_COUNTRY, null);
    }

    /**
     * @return the country code of the SIM card currently inserted in the device.
     */
    private String getSimBasedCountryIso() {
        return mTelephonyManager == null ? null : mTelephonyManager.getSimCountryIso();
    }

    /**
     * @return the country code of the user's currently selected locale.
     */
    private String getLocaleBasedCountryIso() {
        Locale defaultLocale = mLocaleProvider.getDefaultLocale();
        if (defaultLocale != null) {
            return defaultLocale.getCountry();
        }
        return null;
    }

    private boolean isNetworkCountryCodeAvailable() {
        // On CDMA TelephonyManager.getNetworkCountryIso() just returns the SIM's country code.
        // In this case, we want to ignore the value returned and fallback to location instead.
        return mTelephonyManager.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM;
    }

    public static class LocationChangedReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(final Context context, Intent intent) {
            if (!intent.hasExtra(LocationManager.KEY_LOCATION_CHANGED)) {
                return;
            }

            final Location location = (Location)intent.getExtras().get(
                    LocationManager.KEY_LOCATION_CHANGED);

            UpdateCountryService.updateCountry(context, location);
        }
    }

}

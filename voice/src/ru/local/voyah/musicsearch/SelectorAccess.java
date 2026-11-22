package ru.local.voyah.musicsearch;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

final class SelectorAccess {
    private static final String UPDATE_PREFS="selector_update";
    private static final String UPDATE_ENABLED="configured_after_update";

    static boolean containsService(String services,String packageName) {
        if(services==null) return false;
        String shortName=packageName+"/.SearchService";
        String fullName=packageName+"/"+packageName+".SearchService";
        for(String name:services.split(":")) {
            if(name.equals(shortName) || name.equals(fullName)) return true;
        }
        return false;
    }

    static boolean configured(Context context) {
        String services=Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return Settings.Secure.getInt(context.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,0)!=0
                && containsService(services,context.getPackageName());
    }

    static boolean isSettingsPackage(String name,int flags) {
        int systemFlags=ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        return (flags & systemFlags)!=0 && !"com.android.car.frameworkpackagestubs".equals(name);
    }

    static Intent findSettings(Context context) {
        Intent open=new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        for(ResolveInfo handler:context.getPackageManager().queryIntentActivities(open,PackageManager.MATCH_DEFAULT_ONLY)) {
            ActivityInfo activity=handler.activityInfo;
            if(activity==null || activity.applicationInfo==null || !activity.exported || !activity.enabled ||
                    !activity.applicationInfo.enabled ||
                    !isSettingsPackage(activity.packageName,activity.applicationInfo.flags)) continue;
            if(activity.permission!=null && context.checkSelfPermission(activity.permission)!=PackageManager.PERMISSION_GRANTED)
                continue;
            return open.setComponent(new ComponentName(activity.packageName,activity.name));
        }
        return null;
    }

    static void recordAfterUpdate(Context context) {
        boolean enabled=configured(context);
        context.getSharedPreferences(UPDATE_PREFS,Context.MODE_PRIVATE).edit().putBoolean(UPDATE_ENABLED,enabled).apply();
        android.util.Log.i("MusicVoice","После обновления автовыбор: "+(enabled?"включён":"выключен"));
    }

    static String updateStatus(Context context) {
        android.content.SharedPreferences state=context.getSharedPreferences(UPDATE_PREFS,Context.MODE_PRIVATE);
        if(!state.contains(UPDATE_ENABLED)) return "";
        return state.getBoolean(UPDATE_ENABLED,false)?"включён":"выключен";
    }
}

package ru.local.voyah.musicsearch;

final class SelectorAccessTest {
    public static void main(String[] args) {
        String app="ru.local.voyah.musicsearch";
        String another="ru.other/.Service";
        require(SelectorAccess.containsService(another+":"+app+"/.SearchService",app));
        require(SelectorAccess.containsService(app+"/"+app+".SearchService:"+another,app));
        require(!SelectorAccess.containsService(another+":"+app+"/.OtherService",app));
        require(!SelectorAccess.containsService(null,app));
        require(SelectorAccess.isSettingsPackage("com.android.car.settings",android.content.pm.ApplicationInfo.FLAG_SYSTEM));
        require(!SelectorAccess.isSettingsPackage("com.android.car.frameworkpackagestubs",android.content.pm.ApplicationInfo.FLAG_SYSTEM));
        require(!SelectorAccess.isSettingsPackage("com.example.settings",0));
    }
    private static void require(boolean condition) {
        if(!condition) throw new AssertionError("Unexpected Spotify accessibility setup state");
    }
}

package io.github.zopulus.ffc.util;

/** Stable diagnostic names; hook installers and health checks share this catalogue. */
public enum ProtectionId {
    PROXY_WAKELOCK("OplusProxyWakeLock"),
    PROXY_BROADCAST("OplusProxyBroadcast"),
    GMS_RESTRICTION_SETTER("setGmsRestricted"),
    GOOGLE_RESTRICTION_INFO("isGoogleRestricInfoOn"),
    APP_CLASSIFICATION("isAppClassifyRestricted"),
    GCM_BIND("isAllowStartFromBindService"),
    FCM_SERVICE_START("isAllowStartFromStartService"),
    SYSTEM_COMPONENT_RESTRICTION("isSysRestrictionCpn"),
    APP_NETWORK_CONTROL("OAppNetControlService"),
    HANS_SCENE_WINDOW("HansSceneManager FCM window"),
    HANS_CGROUP_WINDOW("HansCGroup FCM window"),
    CPN_PROXY("CpnProxy broadcast"),
    GMS_RESTRICTION_STATE("isGmsRestricted"),
    WEAK_SIGNAL_WHITELIST("weak-signal net whitelist"),
    BROADCAST_PROCESS_START("validStartProcessFromBroadcast"),
    MALICIOUS_BROADCAST("malicious broadcast check"),
    MALICIOUS_SERVICE("malicious service check"),
    LINK_START_BROADCAST("link-start broadcast check"),
    HANS_JOB_WINDOW("Hans job FCM window"),
    NIGHT_WHITELIST("night network whitelist"),
    GOOGLE_FIREWALL("Google network firewall"),
    GOOGLE_RESTRICT_BROADCAST("Google restrict broadcast"),
    GMS_FORCE_STOP("deep-sleep GMS force-stop");
    public final String wireName;
    ProtectionId(String name) { wireName = name; }
}

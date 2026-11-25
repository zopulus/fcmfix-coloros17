package io.github.zopulus.ffc.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.ApplicationInfo;

import org.junit.Test;

public class FcmAppsTest {

    @Test
    public void filtersUninstalledSystemAndModuleSelfPackages() {
        assertTrue(FcmApps.isEligiblePackage("com.tencent.mm", ApplicationInfo.FLAG_INSTALLED));
        assertTrue(FcmApps.isEligiblePackage("com.google.android.gms",
                ApplicationInfo.FLAG_INSTALLED | ApplicationInfo.FLAG_SYSTEM));
        assertFalse(FcmApps.isEligiblePackage("com.tencent.mm", 0));
        assertFalse(FcmApps.isEligiblePackage("android", ApplicationInfo.FLAG_INSTALLED));
        assertFalse(FcmApps.isEligiblePackage("io.github.zopulus.ffc", ApplicationInfo.FLAG_INSTALLED));
        assertFalse(FcmApps.isEligiblePackage("", ApplicationInfo.FLAG_INSTALLED));
        assertFalse(FcmApps.isEligiblePackage(null, ApplicationInfo.FLAG_INSTALLED));
    }
}

package io.github.zopulus.ffc.libxposed;

import org.junit.Test;
import java.io.InputStream;
import static org.junit.Assert.*;

public class XposedHelpersCacheTest {
    static class Parent { private String value = "parent"; }
    static class Child extends Parent { private String value = "child"; }
    public static class Fixture {
        private String value = "initial";
        private String choose(Object value) { return "object"; }
        private String choose(String value) { return "string"; }
        private String choose(Integer value) { return "integer"; }
        private String primitive(int value) { return "int:" + value; }
    }
    @Test public void fieldCacheStoresMetadataNotObjectValues() {
        Fixture first = new Fixture(), second = new Fixture();
        XposedHelpers.setObjectField(first, "value", "changed");
        assertEquals("changed", XposedHelpers.getObjectField(first, "value"));
        assertEquals("initial", XposedHelpers.getObjectField(second, "value"));
        XposedHelpers.setObjectField(first, "value", "again");
        assertEquals("again", XposedHelpers.getObjectField(first, "value"));
    }
    @Test public void inheritedAndShadowedFieldsStaySeparate() {
        assertEquals("parent", XposedHelpers.getObjectField(new Parent(), "value"));
        assertEquals("child", XposedHelpers.getObjectField(new Child(), "value"));
        assertEquals("parent", XposedHelpers.getObjectField(new Parent(), "value"));
    }
    @Test public void overloadRuntimeTypesAndExactSignaturesDoNotCollide() throws Exception {
        Fixture fixture = new Fixture();
        for (int i = 0; i < 3; i++) {
            assertEquals("string", XposedHelpers.callMethod(fixture, "choose", "text"));
            assertEquals("integer", XposedHelpers.callMethod(fixture, "choose", 7));
            assertEquals("object", XposedHelpers.callMethod(fixture, "choose", new Object()));
            assertEquals("int:7", XposedHelpers.callMethod(fixture, "primitive", 7));
            assertEquals("object", XposedHelpers.findMethodExact(Fixture.class, "choose", Object.class)
                    .invoke(fixture, "text"));
        }
    }
    @Test public void nullArgumentHasItsOwnCacheKey() {
        Fixture fixture = new Fixture();
        Object selected = XposedHelpers.callMethod(fixture, "choose", new Object[]{null});
        assertEquals("string", XposedHelpers.callMethod(fixture, "choose", "text"));
        assertEquals(selected, XposedHelpers.callMethod(fixture, "choose", new Object[]{null}));
        assertEquals("integer", XposedHelpers.callMethod(fixture, "choose", 7));
    }
    @Test public void sameNameFromDifferentClassLoadersNeverSharesMembers() throws Exception {
        String name = Fixture.class.getName();
        byte[] bytes;
        try (InputStream stream = Fixture.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            assertNotNull(stream);
            bytes = stream.readAllBytes();
        }
        ClassLoader first = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String candidate, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(candidate)) return super.loadClass(candidate, resolve);
                Class<?> type = findLoadedClass(candidate);
                return type == null ? defineClass(candidate, bytes, 0, bytes.length) : type;
            }
        };
        ClassLoader second = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String candidate, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(candidate)) return super.loadClass(candidate, resolve);
                Class<?> type = findLoadedClass(candidate);
                return type == null ? defineClass(candidate, bytes, 0, bytes.length) : type;
            }
        };
        Class<?> a = first.loadClass(name), b = second.loadClass(name);
        assertNotSame(a, b);
        Object one = a.getDeclaredConstructor().newInstance(), two = b.getDeclaredConstructor().newInstance();
        XposedHelpers.setObjectField(one, "value", "one");
        XposedHelpers.setObjectField(two, "value", "two");
        assertEquals("one", XposedHelpers.getObjectField(one, "value"));
        assertEquals("two", XposedHelpers.getObjectField(two, "value"));
        assertEquals("string", XposedHelpers.callMethod(one, "choose", "x"));
        assertEquals("integer", XposedHelpers.callMethod(two, "choose", 7));
        assertNotSame(XposedHelpers.findMethodExact(a, "choose", String.class),
                XposedHelpers.findMethodExact(b, "choose", String.class));
    }
}

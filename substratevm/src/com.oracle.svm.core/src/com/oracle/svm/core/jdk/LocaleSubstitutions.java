/*
 * Copyright (c) 2013, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.jdk;

// Checkstyle: allow reflection

import java.io.IOException;
import java.lang.ref.ReferenceQueue;
import java.lang.reflect.Field;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.spi.LocaleServiceProvider;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.UnsafeAccess;
import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Delete;
import com.oracle.svm.core.annotate.KeepOriginal;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.RecomputeFieldValue.Kind;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.util.VMError;

import sun.text.normalizer.UBiDiProps;
import sun.text.normalizer.UCharacterProperty;
import sun.util.locale.provider.JRELocaleProviderAdapter;
import sun.util.locale.provider.LocaleProviderAdapter;
import sun.util.locale.provider.LocaleProviderAdapter.Type;
import sun.util.locale.provider.LocaleResources;
import sun.util.locale.provider.LocaleServiceProviderPool;
import sun.util.locale.provider.LocaleServiceProviderPool.LocalizedObjectGetter;

@TargetClass(java.util.Locale.class)
final class Target_java_util_Locale {

    static {
        /*
         * Ensure that default locales are initialized, so that we do not have to do it at run time.
         */
        Locale.getDefault();
        for (Locale.Category category : Locale.Category.values()) {
            Locale.getDefault(category);
        }
    }

    @Substitute
    private static Object initDefault() {
        throw VMError.unsupportedFeature("initalization of Locale");
    }

    @Substitute
    private static Object initDefault(Locale.Category category) {
        throw VMError.unsupportedFeature("initalization of Locale with category " + category);
    }
}

@Substitute
@TargetClass(sun.util.locale.provider.LocaleServiceProviderPool.class)
@SuppressWarnings({"static-method", "unused", "unchecked"})
final class Target_sun_util_locale_provider_LocaleServiceProviderPool {

    /*
     * We make our own caches, which are full populated during native image generation, to avoid any
     * dynamic resource loading at run time. This is a conservative handling of locale-specific
     * parts, but good enough for now.
     */
    private static final Map<Class<? extends LocaleServiceProvider>, Object> cachedPools;

    private final LocaleServiceProvider cachedProvider;

    @Platforms(Platform.HOSTED_ONLY.class)
    protected static Class<LocaleServiceProvider>[] spiClasses() throws NoSuchFieldException, IllegalAccessException {
        /*
         * LocaleServiceProviderPool.spiClasses does not contain all the classes we need, so we list
         * them manually here.
         */
        return (Class<LocaleServiceProvider>[]) new Class<?>[]{java.text.spi.BreakIteratorProvider.class, java.text.spi.CollatorProvider.class, java.text.spi.DateFormatProvider.class,
                        java.text.spi.DateFormatSymbolsProvider.class, java.text.spi.DecimalFormatSymbolsProvider.class, java.text.spi.NumberFormatProvider.class,
                        java.util.spi.CurrencyNameProvider.class, java.util.spi.LocaleNameProvider.class, java.util.spi.TimeZoneNameProvider.class, java.util.spi.CalendarDataProvider.class,
                        java.util.spi.CalendarNameProvider.class};
    }

    static {
        cachedPools = new HashMap<>();
        try {
            Field providersField = LocaleServiceProviderPool.class.getDeclaredField("providers");
            providersField.setAccessible(true);

            for (Class<LocaleServiceProvider> providerClass : spiClasses()) {
                LocaleServiceProviderPool pool = LocaleServiceProviderPool.getPool(providerClass);
                ConcurrentMap<LocaleProviderAdapter.Type, LocaleServiceProvider> providers = (ConcurrentMap<LocaleProviderAdapter.Type, LocaleServiceProvider>) providersField.get(pool);
                LocaleServiceProvider provider = providers.get(LocaleProviderAdapter.Type.JRE);
                assert providers.size() == 1 && provider != null : providers;
                cachedPools.put(providerClass, new Target_sun_util_locale_provider_LocaleServiceProviderPool(provider));
            }
        } catch (Throwable ex) {
            throw VMError.shouldNotReachHere(ex);
        }
    }

    Target_sun_util_locale_provider_LocaleServiceProviderPool(LocaleServiceProvider cachedProvider) {
        this.cachedProvider = cachedProvider;
    }

    @Substitute
    private static LocaleServiceProviderPool getPool(Class<? extends LocaleServiceProvider> providerClass) {
        LocaleServiceProviderPool result = (LocaleServiceProviderPool) cachedPools.get(providerClass);
        if (result == null) {
            throw VMError.unsupportedFeature("LocaleServiceProviderPool.getPool " + providerClass.getName());
        }
        return result;
    }

    @Substitute
    private boolean hasProviders() {
        return false;
    }

    @KeepOriginal
    private native <P extends LocaleServiceProvider, S> S getLocalizedObject(LocalizedObjectGetter<P, S> getter, Locale locale, Object... params);

    @KeepOriginal
    private native <P extends LocaleServiceProvider, S> S getLocalizedObject(LocalizedObjectGetter<P, S> getter, Locale locale, String key, Object... params);

    @Substitute
    private <P extends LocaleServiceProvider, S> S getLocalizedObjectImpl(LocalizedObjectGetter<P, S> getter, Locale locale, boolean isObjectProvider, String key, Object... params) {
        if (locale == null) {
            throw new NullPointerException();
        }
        return getter.getObject((P) cachedProvider, locale, key, params);
    }
}

@TargetClass(sun.util.locale.provider.LocaleProviderAdapter.class)
@SuppressWarnings({"unused"})
final class Target_sun_util_locale_provider_LocaleProviderAdapter {

    @Substitute
    public static LocaleProviderAdapter getAdapter(Class<? extends LocaleServiceProvider> providerClass, Locale locale) {
        LocaleProviderAdapter result = Util_sun_util_locale_provider_LocaleProviderAdapter.cachedAdapters.get(providerClass);
        if (result == null) {
            throw VMError.unsupportedFeature("LocaleServiceProviderAdapter.getAdapter " + providerClass.getName());
        }
        return result;
    }

    @Alias private static LocaleProviderAdapter jreLocaleProviderAdapter;

    @Substitute
    public static LocaleProviderAdapter forType(Type type) {
        if (type == Type.JRE) {
            return jreLocaleProviderAdapter;
        } else {
            throw VMError.unsupportedFeature("LocaleProviderAdapter.forType: " + type);
        }
    }
}

final class Util_sun_util_locale_provider_LocaleProviderAdapter {

    static final Map<Class<? extends LocaleServiceProvider>, LocaleProviderAdapter> cachedAdapters;

    static {
        cachedAdapters = new HashMap<>();

        try {
            Class<LocaleServiceProvider>[] spiClasses = Target_sun_util_locale_provider_LocaleServiceProviderPool.spiClasses();
            for (Class<LocaleServiceProvider> providerClass : spiClasses) {
                LocaleProviderAdapter adapter = LocaleProviderAdapter.getAdapter(providerClass, Locale.getDefault());
                assert adapter.getClass() == JRELocaleProviderAdapter.class;
                cachedAdapters.put(providerClass, adapter);
            }

        } catch (Throwable ex) {
            throw VMError.shouldNotReachHere(ex);
        }

    }
}

@Delete
@TargetClass(sun.util.locale.provider.AuxLocaleProviderAdapter.class)
final class Target_sun_util_locale_provider_AuxLocaleProviderAdapter {
}

@TargetClass(sun.text.normalizer.UCharacterProperty.class)
final class Target_sun_text_normalizer_UCharacterProperty {

    @Substitute
    private static UCharacterProperty getInstance() {
        return Util_sun_text_normalizer_UCharacterProperty.instance;
    }
}

final class Util_sun_text_normalizer_UCharacterProperty {
    static final UCharacterProperty instance = UCharacterProperty.getInstance();
}

@TargetClass(sun.text.normalizer.UBiDiProps.class)
final class Target_sun_text_normalizer_UBiDiProps {

    @Substitute
    private static UBiDiProps getSingleton() {
        return Util_sun_text_normalizer_UBiDiProps.singleton;
    }
}

final class Util_sun_text_normalizer_UBiDiProps {

    static final UBiDiProps singleton;

    static {
        UnsafeAccess.UNSAFE.ensureClassInitialized(sun.text.normalizer.NormalizerImpl.class);

        try {
            singleton = UBiDiProps.getSingleton();
        } catch (IOException ex) {
            throw VMError.shouldNotReachHere(ex);
        }
    }
}

@TargetClass(java.util.TimeZone.class)
final class Target_java_util_TimeZone {

    @Substitute
    private static TimeZone getDefaultRef() {
        return Util_java_util_TimeZone.defaultZone;
    }

    @Substitute
    private static void setDefault(TimeZone zone) {
        Util_java_util_TimeZone.defaultZone = zone;
    }

    @Substitute
    public static TimeZone getTimeZone(String id) {
        for (TimeZone zone : Util_java_util_TimeZone.zones) {
            if (zone.getID().equals(id)) {
                return zone;
            }
        }
        return Util_java_util_TimeZone.zones.get(0);
    }
}

final class Util_java_util_TimeZone {

    protected static final List<TimeZone> zones;
    protected static TimeZone defaultZone = TimeZone.getDefault();

    static {
        defaultZone = TimeZone.getDefault();
        zones = new ArrayList<>();
        /* The first entry must be GMT, it is returned when no other match found. */
        zones.add(TimeZone.getTimeZone("GMT"));
        zones.add(TimeZone.getTimeZone("UTC"));
        zones.add(defaultZone);
    }
}

@TargetClass(java.text.BreakIterator.class)
final class Target_java_text_BreakIterator {

    @Substitute
    private static BreakIterator getWordInstance(Locale locale) {
        assert locale == Locale.getDefault();
        return (BreakIterator) Util_java_text_BreakIterator.WORD_INSTANCE.clone();
    }

    @Substitute
    private static BreakIterator getLineInstance(Locale locale) {
        assert locale == Locale.getDefault();
        return (BreakIterator) Util_java_text_BreakIterator.LINE_INSTANCE.clone();
    }

    @Substitute
    private static BreakIterator getCharacterInstance(Locale locale) {
        assert locale == Locale.getDefault();
        return (BreakIterator) Util_java_text_BreakIterator.CHARACTER_INSTANCE.clone();
    }

    @Substitute
    private static BreakIterator getSentenceInstance(Locale locale) {
        assert locale == Locale.getDefault();
        return (BreakIterator) Util_java_text_BreakIterator.SENTENCE_INSTANCE.clone();
    }
}

@TargetClass(LocaleResources.class)
final class Target_sun_util_locale_provider_LocaleResources {
    @RecomputeFieldValue(kind = Kind.NewInstance, declClass = ConcurrentHashMap.class)//
    @Alias//
    private ConcurrentMap<?, ?> cache = new ConcurrentHashMap<>();
    @RecomputeFieldValue(kind = Kind.NewInstance, declClass = ReferenceQueue.class)//
    @Alias//
    private ReferenceQueue<Object> referenceQueue = new ReferenceQueue<>();
}

@TargetClass(JRELocaleProviderAdapter.class)
final class Target_sun_util_locale_provider_JRELocaleProviderAdapter {
    @RecomputeFieldValue(kind = Kind.NewInstance, declClass = ConcurrentHashMap.class)//
    @Alias//
    private final ConcurrentMap<String, Set<String>> langtagSets = new ConcurrentHashMap<>();

    @RecomputeFieldValue(kind = Kind.NewInstance, declClass = ConcurrentHashMap.class)//
    @Alias//
    private final ConcurrentMap<Locale, LocaleResources> localeResourcesMap = new ConcurrentHashMap<>();
}

final class Util_java_text_BreakIterator {
    static final BreakIterator WORD_INSTANCE = BreakIterator.getWordInstance();
    static final BreakIterator LINE_INSTANCE = BreakIterator.getLineInstance();
    static final BreakIterator CHARACTER_INSTANCE = BreakIterator.getCharacterInstance();
    static final BreakIterator SENTENCE_INSTANCE = BreakIterator.getSentenceInstance();
}

/** Dummy class to have a class with the file's name. */
public final class LocaleSubstitutions {

    public static void registerTimeZone(TimeZone zone) {
        Util_java_util_TimeZone.zones.add(zone);
    }
}

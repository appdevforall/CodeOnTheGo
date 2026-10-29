package com.termux.app;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;

import java.lang.reflect.Field;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;

@RunWith(RobolectricTestRunner.class)
public class TermuxServicePropertiesNpeTest {

    @Before
    public void clearPropertiesSingleton() throws Exception {
        Field singleton = TermuxAppSharedProperties.class.getDeclaredField("properties");
        singleton.setAccessible(true);
        singleton.set(null, null);
    }

    @Test
    public void onCreateBeforeTheAppLoaderRan_initializesTheServiceProperties() throws Exception {
        assertNull(TermuxAppSharedProperties.getProperties());

        ServiceController<TermuxService> controller = Robolectric.buildService(TermuxService.class).create();

        Object serviceProperties = servicePropertiesOf(controller.get());
        assertNotNull("TermuxService.mProperties must not be null after onCreate()", serviceProperties);
        assertSame(TermuxAppSharedProperties.getProperties(), serviceProperties);

        controller.destroy();
    }

    @Test
    public void onCreateAfterTheAppLoaderRan_reusesTheLoadedProperties() throws Exception {
        TermuxAppSharedProperties loaded = TermuxAppSharedProperties.init(RuntimeEnvironment.getApplication());

        ServiceController<TermuxService> controller = Robolectric.buildService(TermuxService.class).create();

        assertSame(loaded, servicePropertiesOf(controller.get()));

        controller.destroy();
    }

    private static Object servicePropertiesOf(TermuxService service) throws Exception {
        Field field = TermuxService.class.getDeclaredField("mProperties");
        field.setAccessible(true);
        return field.get(service);
    }
}

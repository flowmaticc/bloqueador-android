package app.bloqueador.update

import app.bloqueador.lists.ExtraLayers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorLayerTest {

    @Test
    fun `lista del fabricante segun la marca`() {
        assertTrue(ExtraLayers.vendorFor("samsung")!!.url.endsWith("/native.samsung.txt"))
        assertTrue(ExtraLayers.vendorFor("Xiaomi")!!.url.endsWith("/native.xiaomi.txt"))
        assertTrue(ExtraLayers.vendorFor("POCO")!!.url.endsWith("/native.xiaomi.txt"))
        assertTrue(ExtraLayers.vendorFor("OnePlus")!!.url.endsWith("/native.oppo-realme.txt"))
        assertTrue(ExtraLayers.vendorFor("HONOR")!!.url.endsWith("/native.huawei.txt"))
        assertNull("Google no tiene lista propia", ExtraLayers.vendorFor("Google"))
        assertNull(ExtraLayers.vendorFor(null))
    }

    @Test
    fun `la capa del fabricante va la primera y las demas siguen igual`() {
        val layers = ExtraLayers.available("samsung")
        assertEquals("vendor", layers.first().id)
        assertEquals(ExtraLayers.all, layers.drop(1))
        assertEquals(ExtraLayers.all, ExtraLayers.available("Google"))
    }
}

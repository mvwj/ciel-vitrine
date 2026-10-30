package com.cielvitrine.app

import org.junit.Assert.*
import org.junit.Test

class MediaCapabilitiesTest {
    @Test fun animationRequiresFramesAndRawIsNeverAdvertisedAsDecoded() {
        assertEquals(MediaViewCapability.LOCAL_ANIMATION,MediaCapabilities.photo("image/gif"))
        assertEquals(MediaViewCapability.LOCAL_ANIMATION,MediaCapabilities.photo("image/webp"))
        for(mime in listOf("image/x-adobe-dng","image/x-canon-cr2","application/octet-stream")) {
            assertEquals(MediaViewCapability.OPAQUE_ONLY,MediaCapabilities.photo(mime))
            assertNotNull(MediaCapabilities.notice(mime))
        }
        assertEquals(MediaViewCapability.PLATFORM_DECODER,MediaCapabilities.photo("image/heic"))
        assertEquals(MediaViewCapability.PREPARED_TILES,MediaCapabilities.photo("image/png"))
    }
    @Test fun knownCompoundResourcesDoNotTurnIntoAPlatformDecoderOrProofOfCompleteness() {
        val mime="application/vnd.apple.live-photo"
        assertEquals(MediaViewCapability.COMPOUND_UNRESOLVED,MediaCapabilities.photo(mime))
        assertEquals(MediaViewCapability.COMPOUND_RESOURCES,MediaCapabilities.photo(mime,compoundKnown=true))
        assertNotNull(MediaCapabilities.notice(mime))
    }
}

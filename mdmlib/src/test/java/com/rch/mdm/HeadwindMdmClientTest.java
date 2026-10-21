package com.rch.mdm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HeadwindMdmClientTest {

    @Test
    public void md5UppercaseMatchesHeadwindFormat() {
        assertEquals("5F4DCC3B5AA765D61D8327DEB882CF99",
                HeadwindMdmClient.md5Uppercase("password"));
    }

    @Test
    public void baseUrlTrailingSlashesAreStripped() {
        MdmConfig config = new MdmConfig("https://mdm-dev.mnyrnd.com///", "admin", "x");
        assertEquals("https://mdm-dev.mnyrnd.com", config.getBaseUrl());
    }
}

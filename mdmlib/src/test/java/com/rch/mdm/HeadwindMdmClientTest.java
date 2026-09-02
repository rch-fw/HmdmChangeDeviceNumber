package com.rch.mdm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HeadwindMdmClientTest {

    @Test
    public void md5UppercaseMatchesHeadwindFormat() {
        assertEquals("B6301C9378E343CC6DE894437A9D1386",
                HeadwindMdmClient.md5Uppercase("Chiesetta32H"));
    }

    @Test
    public void baseUrlTrailingSlashesAreStripped() {
        MdmConfig config = new MdmConfig("https://mdm-dev.mnyrnd.com///", "admin", "x");
        assertEquals("https://mdm-dev.mnyrnd.com", config.getBaseUrl());
    }
}

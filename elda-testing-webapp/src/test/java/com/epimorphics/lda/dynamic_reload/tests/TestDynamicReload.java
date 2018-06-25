package com.epimorphics.lda.dynamic_reload.tests;

import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;

import org.apache.http.client.ClientProtocolException;
import org.junit.Test;

import com.epimorphics.lda.restlets.RouterRestlet;
import com.epimorphics.lda.systemtest.Util;
import com.epimorphics.lda.testing.utils.TomcatTestBase;

/**
	Test that tweaking a file will cause a reload of the configs.
*/
public class TestDynamicReload extends TomcatTestBase {

	@Override public String getWebappRoot() {
		return "src/main/webapp";
	}

	@Test public void testDynamicReload() throws ClientProtocolException, IOException, InterruptedException {
		Util.testHttpRequest( "games", 200, Util.ignore );
		
		int lastNumber = RouterRestlet.loadCounter;
		tweak("elda-config.ttl");

		Thread.sleep(1000);
		Util.testHttpRequest( "games", 200, Util.ignore );
		
		if(RouterRestlet.loadCounter > lastNumber) {
			// OK
		} else {
			fail("did not reload: semained at " + RouterRestlet.loadCounter  );
		}
	}

	private void tweak(String filePath) {
		File file = new File("src/main/webapp", filePath);
		file.setLastModified(System.currentTimeMillis());		
	}
}

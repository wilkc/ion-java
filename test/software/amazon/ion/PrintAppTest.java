// Copyright (c) 2019 Amazon.com, Inc.  All rights reserved.

package software.amazon.ion;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import software.amazon.ion.apps.PrintApp;

/**
 *
 */
public class PrintAppTest extends IonTestCase
{
    PrintStream oldErr = System.err;
    ByteArrayOutputStream errStream = new ByteArrayOutputStream();

    @Before
    public void captureErr() {
        System.setErr(new PrintStream(errStream));
    }

    @After
    public void releaseErr() {
        System.setErr(oldErr);
    }


    @Test
    public void testUnreadableFile() {
        String[] args = new String[1];
        args[0] = "test.txt";
        PrintApp.main(args);
        assertEquals("Skipping unreadable file: test.txt", errStream.toString().trim());
    }

}

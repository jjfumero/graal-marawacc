/*
 * Copyright (c) 1998, 2007, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package com.sun.hotspot.igv.data.serialization;

import com.sun.hotspot.igv.data.GraphDocument;
import com.sun.hotspot.igv.data.Group;
import com.sun.hotspot.igv.data.InputBlock;
import com.sun.hotspot.igv.data.InputEdge;
import com.sun.hotspot.igv.data.InputGraph;
import com.sun.hotspot.igv.data.InputMethod;
import com.sun.hotspot.igv.data.InputNode;
import com.sun.hotspot.igv.data.Util;
import java.io.CharArrayWriter;
import java.io.StringReader;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openide.xml.XMLUtil;
import static org.junit.Assert.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 *
 * @author Thomas Wuerthinger
 */
public class ParserTest {

    public ParserTest() {
    }

    @BeforeClass
    public static void setUpClass() throws Exception {
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
    }

    @Before
    public void setUp() {
    }

    @After
    public void tearDown() {
    }

    private void test(GraphDocument document) {
        final Printer printer = new Printer();
        final CharArrayWriter writer = new CharArrayWriter();
        printer.export(writer, document);
        test(document, writer.toString());
    }

    private void test(GraphDocument document, String xmlString) {
        
        StringReader sr = new StringReader(xmlString);
        InputSource is = new InputSource(sr);

        try {
            XMLReader reader = XMLUtil.createXMLReader();
            Parser parser = new Parser();
            final GraphDocument parsedDocument = parser.parse(reader, is, null);
            Util.assertGraphDocumentEquals(document, parsedDocument);
        } catch (SAXException ex) {
            fail(ex.toString());
        }
    }

    private void testBoth(GraphDocument document, String xmlString) {
        test(document);
        test(document, xmlString);
    }

    /**
     * Test of graph document serialization
     */
    @Test
    public void testSerialization() {
        final GraphDocument doc = new GraphDocument();

        test(doc);

        final Group group1 = new Group();
        doc.addGroup(group1);
        test(doc);

        final Group group2 = new Group();
        doc.addGroup(group2);
        test(doc);

        final InputGraph graph = group1.addGraph("");
        test(doc);

        graph.addNode(new InputNode(0));
        test(doc);

        graph.addNode(new InputNode(1));
        test(doc);

        graph.addNode(new InputNode(2));
        test(doc);

        graph.addNode(new InputNode(3));
        test(doc);

        graph.addEdge(new InputEdge((char)0, (char)0, 0, 1));
        test(doc);

        graph.addEdge(new InputEdge((char)1, (char)1, 0, 1));
        test(doc);

        graph.addEdge(new InputEdge((char)0, (char)0, 1, 2));
        test(doc);
        
        graph.addEdge(new InputEdge((char)0, (char)0, 2, 3));
        test(doc);

        group1.setMethod(new InputMethod(group1, "testMethod", "tM", 1));
        test(doc);

        final InputBlock b1 = graph.addBlock("1");
        b1.addNode(0);
        b1.addNode(1);

        final InputBlock b2 = graph.addBlock("2");
        b2.addNode(2);
        b2.addNode(3);
        test(doc);

        final GraphDocument document2 = new GraphDocument();
        doc.addGraphDocument(document2);
        test(doc);
        assertTrue(doc.getGroups().size() == 2);

        final Group group3 = new Group();
        document2.addGroup(group3);
        doc.addGraphDocument(document2);
        assertTrue(doc.getGroups().size() == 3);
        assertTrue(document2.getGroups().size() == 0);

        doc.clear();
        test(doc);
        Util.assertGraphDocumentEquals(doc, new GraphDocument());
    }

	@Test
	public void testSimpleExport() {
		GraphDocument document = new GraphDocument();
		Group g = new Group();
		document.addGroup(g);
        
		InputGraph graph = g.addGraph("TestGraph");
		graph.getProperties().setProperty("testName", "testValue");

		InputNode n1 = new InputNode(0);
		InputNode n2 = new InputNode(1);
		InputEdge e1 = new InputEdge((char)0, 0, 1);
		InputEdge e2 = new InputEdge((char)1, 0, 1);
		graph.addNode(n1);
		graph.addNode(n2);
		graph.addEdge(e1);
		graph.addEdge(e2);
        
        test(document);
	}

	@Test
	public void testComplexExport() {

		GraphDocument document = new GraphDocument();
		Group g = new Group();
		document.addGroup(g);

		InputGraph graph = g.addGraph("TestGraph");
		graph.getProperties().setProperty("testName", "testValue");

		InputNode n1 = new InputNode(0);
		InputNode n2 = new InputNode(1);
		InputEdge e1 = new InputEdge((char)0, 0, 0);
		InputEdge e2 = new InputEdge((char)1, 1, 1);
		graph.addNode(n1);
		graph.addNode(n2);
		graph.addEdge(e1);
		graph.addEdge(e2);

		InputGraph graph2 = g.addGraph("TestGraph2");
		graph2.addNode(n1);
		InputNode n3 = new InputNode(2);
		graph2.addNode(n3);
		InputEdge e3 = new InputEdge((char)0, 0, 2);
		graph2.addEdge(e3);

        test(document);
	}


    /**
     * Test of parse method, of class Parser.
     */
    @Test
    public void testParse() {
        testBoth(new GraphDocument(), "<graphDocument></graphDocument>");
    }

}
/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 * 
 *    (C) 2004-2008, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geotools.geometry.jts;

// J2SE dependencies
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.geotools.referencing.operation.transform.ProjectiveTransform;
import org.junit.Test;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;

import com.vividsolutions.jts.geom.CoordinateSequence;
import com.vividsolutions.jts.geom.CoordinateSequenceFilter;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;

/**
 * Tests the {@link GeometryCoordinateSequenceTransformer} implementation.
 * 
 * @since 2.2
 * 
 * 
 * @source $URL$
 * @version $Id$
 * @author Martin Davis
 */
public class GeometryCoordinateSequenceTransformerTest {
    
    private GeometryFactory geomFact = new GeometryFactory(
            new LiteCoordinateSequenceFactory());
    
    private GeometryBuilder gb = new GeometryBuilder(geomFact);
    
    @Test
    public void testLineString() throws Exception {
        checkTransform(gb.lineStringZ(10, 11, 1, 20, 21, 2));
        checkTransform(gb.lineString(10, 11, 20, 21));
    }
    
    @Test
    public void testPoint() throws Exception {
        checkTransform(gb.point(10, 11));
        checkTransform(gb.pointZ(10, 11, 1));
    }
    
    @Test
    public void testPolygon() throws Exception {
        checkTransform(gb.circle(10, 10, 5, 20));
        checkTransform(gb.boxZ(10, 10, 20, 20, 99));
        checkTransform(gb.polygon(gb.boxZ(10, 10, 20, 20, 99),
                gb.boxZ(11, 11, 19, 19, 99)));
    }
    
    @Test
    public void testMulti() throws Exception {
        checkTransform(gb.multiPoint(10, 10, 5, 20));
        checkTransform(gb.multiLineString(gb.lineString(10, 10, 20, 20),
                gb.lineString(10, 10, 20, 20)));
        checkTransform(gb.multiPolygon(gb.boxZ(10, 10, 20, 20, 99),
                gb.boxZ(11, 11, 19, 19, 99)));
    }
    
    @Test
    public void testGeometryCollection() throws Exception {
        checkTransform(gb.geometryCollection(gb.point(10, 11),
                gb.lineString(10, 10, 20, 20), gb.box(10, 10, 20, 20)));
    }
    
    /**
     * Confirm that testing method is accurate!
     * 
     * @throws Exception
     */
    @Test
    public void testDifferentDimensionsFailure() throws Exception {
        Geometry g1 = gb.box(10, 10, 20, 20);
        Geometry g2 = gb.boxZ(10, 10, 20, 20, 99);
        assertFalse(hasSameValuesAndStructure(g1, g2));
    }
    
    private static final double ORD_TOLERANCE = 1.0e-6;
    
    /**
     * Check transformation correctness by transforming forwards and backwards using
     * inverse MathTransforms.
     * 
     * @param g
     * @throws TransformException
     */
    private void checkTransform(Geometry g) throws TransformException {
        GeometryCoordinateSequenceTransformer gcsTrans = new GeometryCoordinateSequenceTransformer();
        gcsTrans.setCoordinateReferenceSystem(DefaultGeographicCRS.WGS84);
        MathTransform trans = ProjectiveTransform.createTranslation(2, 100);
        gcsTrans.setMathTransform(trans);
    
        GeometryCoordinateSequenceTransformer gcsTransInv = new GeometryCoordinateSequenceTransformer();
        gcsTransInv.setCoordinateReferenceSystem(DefaultGeographicCRS.WGS84);
        MathTransform transInv = ProjectiveTransform.createTranslation(2, -100);
        gcsTransInv.setMathTransform(transInv);
    
        Geometry gTrans = gcsTrans.transform(g);
        Geometry g2 = gcsTransInv.transform(gTrans);
    
        // result better be a different geometry
        assertTrue(g != g2);
        assertTrue(hasSameValuesAndStructure(g, g2));
    }
    
    boolean hasSameValuesAndStructure(Geometry g1, Geometry g2) {
        if (!g1.equalsExact(g2, ORD_TOLERANCE))
            return false;
        if (g1.getFactory() != g2.getFactory())
            return false;
    
        CoordinateSequence seq = CoordinateSequenceFinder.find(g1);
        if (!CoordinateSequenceSchemaChecker.check(g2, seq.getClass(),
                seq.getDimension()))
            return false;
        return true;
    }
    
    static class CoordinateSequenceFinder implements CoordinateSequenceFilter {

        public static CoordinateSequence find(Geometry g) {
            CoordinateSequenceFinder finder = new CoordinateSequenceFinder();
            g.apply(finder);
            return finder.getSeq();
        }
        
        private CoordinateSequence firstSeqFound = null;
        
        public CoordinateSequence getSeq() {
            return firstSeqFound;
        }
        
        public void filter(CoordinateSequence seq, int i) {
            if (firstSeqFound == null)
                firstSeqFound = seq;
        
        }
        
        public boolean isDone() {
            return firstSeqFound != null;
        }
        
        public boolean isGeometryChanged() {
            return false;
        }
    }
    
    static class CoordinateSequenceSchemaChecker implements
            CoordinateSequenceFilter {
   
        public static boolean check(Geometry g, Class coordSeqClass, int dimension) {
            CoordinateSequenceSchemaChecker checkCS = new CoordinateSequenceSchemaChecker(
                    coordSeqClass, dimension);
            g.apply(checkCS);
            return checkCS.isSame();
        }
        
        private Class coordSeqClass;
        
        private int dimension;
        
        private boolean isSame = true;
        
        public CoordinateSequenceSchemaChecker(Class coordSeqClass, int dimension) {
            this.coordSeqClass = coordSeqClass;
            this.dimension = dimension;
        }
        
        public boolean isSame() {
            return isSame;
        }
    
        public void filter(CoordinateSequence seq, int i) {
            if (seq.getClass() != coordSeqClass)
                isSame = false;
            if (seq.getDimension() != dimension)
                isSame = false;
        }
    
        public boolean isDone() {
            return !isSame;
        }
        
    
        public boolean isGeometryChanged() {
            return false;
        }
    
    }

}

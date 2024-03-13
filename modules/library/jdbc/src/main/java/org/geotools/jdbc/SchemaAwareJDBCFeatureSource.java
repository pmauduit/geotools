package org.geotools.jdbc;

import java.io.IOException;
import java.sql.*;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.geotools.api.data.FeatureReader;
import org.geotools.api.data.Query;
import org.geotools.api.data.Transaction;
import org.geotools.api.feature.Association;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.feature.type.AttributeDescriptor;
import org.geotools.api.filter.Filter;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.data.FilteringFeatureReader;
import org.geotools.data.MaxFeatureReader;
import org.geotools.data.ReTypeFeatureReader;
import org.geotools.data.store.ContentEntry;
import org.geotools.feature.AttributeTypeBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.util.factory.Hints;
import org.locationtech.jts.geom.Geometry;

public class SchemaAwareJDBCFeatureSource extends JDBCFeatureSource {

    public SchemaAwareJDBCFeatureSource(ContentEntry entry, Query query) throws IOException {
        super(entry, query);
    }

    @Override
    protected SimpleFeatureType buildFeatureType() throws IOException {
        // grab the primary key
        PrimaryKey pkey = getDataStore().getPrimaryKey(entry);
        VirtualTable virtualTable = getDataStore().getVirtualTables().get(entry.getTypeName());

        SimpleFeatureTypeBuilder tb = new SimpleFeatureTypeBuilder();
        AttributeTypeBuilder ab = new AttributeTypeBuilder();
        ab.setSeparator(entry.getName().getSeparator());
        tb.setSeparator(entry.getName().getSeparator());
        // setup the read only marker if no pk or null pk or it's a view
        boolean readOnly = false;
        if (pkey == null || pkey instanceof NullPrimaryKey || virtualTable != null) {
            readOnly = true;
        }

        // set up the fully qualified table name
        tb.setName(entry.getName());
        String tableName = entry.getName().toString(); // TODO escape with ""."" ?

        // set the namespace, if not null
        if (entry.getName().getNamespaceURI() != null) {
            tb.setNamespaceURI(entry.getName().getNamespaceURI());
        } else {
            // use the data store
            tb.setNamespaceURI(getDataStore().getNamespaceURI());
        }

        // grab the state
        JDBCState state = getState();

        // grab the schema
        String databaseSchema = entry.getName().getNamespaceURI();

        // ensure we have a connection
        Connection cx = getDataStore().getConnection(state);

        // grab the dialect
        SQLDialect dialect = getDataStore().getSQLDialect();

        // logger
        final Logger storeLogger = getDataStore().getLogger();

        // get metadata about columns from database
        try {
            DatabaseMetaData metaData = cx.getMetaData();
            // get metadata about columns from database
            List<ColumnMetadata> columns;
            if (virtualTable != null) {
                columns = getColumnMetadata(cx, virtualTable, dialect, getDataStore());
            } else {
                columns = getColumnMetadata(cx, databaseSchema, tableName, dialect);
            }

            for (ColumnMetadata column : columns) {
                String name = column.name;

                // do not include primary key in the type if not exposing primary key columns
                for (PrimaryKeyColumn pkeycol : pkey.getColumns()) {
                    if (name.equals(pkeycol.getName())) {
                        if (!state.isExposePrimaryKeyColumns()) {
                            name = null;
                            break;
                        }
                    }
                    // in views we don't know the pk type, grab it now
                    if (pkeycol.type == null) {
                        pkeycol.type = column.binding;
                    }
                }

                if (name == null) {
                    continue;
                }

                // check for association
                if (getDataStore().isAssociations()) {
                    getDataStore().ensureAssociationTablesExist(cx);

                    // check for an association
                    Statement st = null;
                    ResultSet relationships = null;
                    if (getDataStore().getSQLDialect() instanceof PreparedStatementSQLDialect) {
                        st = getDataStore().selectRelationshipSQLPS(tableName, name, cx);
                        relationships = ((PreparedStatement) st).executeQuery();
                    } else {
                        String sql = getDataStore().selectRelationshipSQL(tableName, name);
                        storeLogger.fine(sql);

                        st = cx.createStatement();
                        relationships = st.executeQuery(sql);
                    }

                    try {
                        if (relationships.next()) {
                            // found, create a special mapping
                            tb.add(name, Association.class);

                            continue;
                        }
                    } finally {
                        getDataStore().closeSafe(relationships);
                        getDataStore().closeSafe(st);
                    }
                }

                // first ask the dialect
                Class binding = column.binding;

                if (binding == null) {
                    // determine from type name mappings
                    binding = getDataStore().getMapping(column.typeName);
                }

                if (binding == null) {
                    // determine from type mappings
                    binding = getDataStore().getMapping(column.sqlType);
                }

                // if still not found, ignore the column we don't know about
                if (binding == null) {
                    storeLogger.warning(
                            "Could not find mapping for '"
                                    + name
                                    + "', ignoring the column and setting the feature type read only");
                    readOnly = true;
                    continue;
                }

                // store the native database type in the attribute descriptor user data
                ab.addUserData(JDBCDataStore.JDBC_NATIVE_TYPENAME, column.typeName);
                ab.addUserData(JDBCDataStore.JDBC_NATIVE_TYPE, column.sqlType);

                // nullability
                if (!column.nullable) {
                    ab.nillable(false);
                    ab.minOccurs(1);
                }
                if (column.restriction != null) {
                    ab.addRestriction(column.restriction);
                }
                if (column.getRemarks() != null && !column.getRemarks().isEmpty()) {
                    ab.setDescription(column.getRemarks());
                }

                AttributeDescriptor att = null;

                // determine if this attribute is a geometry or not
                if (Geometry.class.isAssignableFrom(binding)) {
                    // add the attribute as a geometry, try to figure out
                    // its srid first
                    Integer srid = null;
                    CoordinateReferenceSystem crs = null;
                    try {
                        if (virtualTable != null) {
                            srid = virtualTable.getNativeSrid(name);
                        } else {
                            srid = dialect.getGeometrySRID(databaseSchema, tableName, name, cx);
                        }
                        if (srid != null) {
                            crs = dialect.createCRS(srid, cx);
                            if (crs == null) {
                                storeLogger.warning(
                                        "Couldn't determine CRS of table "
                                                + tableName
                                                + " with srid: "
                                                + srid
                                                + ".");
                            }
                        } else {
                            storeLogger.info("No srid returned of database table:" + tableName);
                        }
                    } catch (Exception e) {
                        String msg = "Error occured determing srid for " + tableName + "." + name;
                        storeLogger.log(Level.WARNING, msg, e);
                    }

                    // compute the dimension too
                    int dimension = 2;
                    try {
                        if (virtualTable != null) {
                            dimension = virtualTable.getDimension(name);
                        } else {
                            dimension =
                                    dialect.getGeometryDimension(
                                            databaseSchema, tableName, name, cx);
                        }
                    } catch (Exception e) {
                        String msg =
                                "Error occured determing dimension for " + tableName + "." + name;
                        storeLogger.log(Level.WARNING, msg, e);
                    }

                    ab.setBinding(binding);
                    ab.setName(name);
                    ab.setCRS(crs);
                    if (srid != null) {
                        ab.addUserData(JDBCDataStore.JDBC_NATIVE_SRID, srid);
                    }
                    ab.addUserData(Hints.COORDINATE_DIMENSION, dimension);
                    att = ab.buildDescriptor(name, ab.buildGeometryType());
                } else {
                    // add the attribute
                    ab.setName(name);
                    ab.setBinding(binding);
                    att = ab.buildDescriptor(name, ab.buildType());
                }
                // mark primary key columns
                if (pkey.getColumn(att.getLocalName()) != null) {
                    att.getUserData().put(JDBCDataStore.JDBC_PRIMARY_KEY_COLUMN, true);
                }

                // call dialect callback
                dialect.postCreateAttribute(att, tableName, databaseSchema, cx);
                tb.add(att);
            }

            // build the final type
            SimpleFeatureType ft = tb.buildFeatureType();

            // mark it as read only if necessary
            // (the builder userData method affects attributes, not the ft itself)
            if (readOnly) {
                ft.getUserData().put(JDBCDataStore.JDBC_READ_ONLY, Boolean.TRUE);
            }

            // call dialect callback
            dialect.postCreateFeatureType(ft, metaData, databaseSchema, cx);
            return ft;
        } catch (SQLException e) {
            String msg = "Error occurred building feature type";
            throw (IOException) new IOException(msg).initCause(e);
        } finally {
            getDataStore().releaseConnection(cx, state);
        }
    }

    @Override
    @SuppressWarnings("PMD.CloseResource") // the cx is passed to the reader which will close it
    protected FeatureReader<SimpleFeatureType, SimpleFeature> getReaderInternal(Query query)
            throws IOException {
        // split the filter
        Filter[] split = splitFilter(query.getFilter());
        Filter preFilter = split[0];
        Filter postFilter = split[1];
        boolean postFilterRequired = postFilter != null && postFilter != Filter.INCLUDE;

        // rebuild a new query with the same params, but just the pre-filter
        Query preQuery = new Query(query);
        preQuery.setFilter(preFilter);
        // in case of post filtering, we cannot do native paging
        if (postFilterRequired) {
            preQuery.setStartIndex(0);
            preQuery.setMaxFeatures(Integer.MAX_VALUE);
        }

        // Build the feature type returned by this query. Also build an eventual extra feature type
        // containing the attributes we might need in order to evaluate the post filter
        SimpleFeatureType[] types =
                buildQueryAndReturnFeatureTypes(getSchema(), query.getPropertyNames(), postFilter);
        SimpleFeatureType querySchema = types[0];
        SimpleFeatureType returnedSchema = types[1];

        // grab connection
        Connection cx = getDataStore().getConnection(getState());

        // create the reader
        FeatureReader<SimpleFeatureType, SimpleFeature> reader;

        try {
            SQLDialect dialect = getDataStore().getSQLDialect();

            // allow dialect to override this if needed
            if (getState().getTransaction() == Transaction.AUTO_COMMIT) {
                cx.setAutoCommit(dialect.isAutoCommitQuery());
            }

            if (query.getJoins().isEmpty()) {
                // regular query
                if (dialect instanceof PreparedStatementSQLDialect) {
                    PreparedStatement ps = getDataStore().selectSQLPS(querySchema, preQuery, cx);
                    reader = new JDBCFeatureReader(ps, cx, this, querySchema, query);
                } else {
                    // build up a statement for the content
                    String sql = getDataStore().selectSQL(querySchema, preQuery);
                    getDataStore().getLogger().fine(sql);

                    reader = new JDBCFeatureReader(sql, cx, this, querySchema, query);
                }
            } else {
                JoinInfo join = JoinInfo.create(preQuery, this);

                if (dialect instanceof PreparedStatementSQLDialect) {
                    PreparedStatement ps =
                            getDataStore().selectJoinSQLPS(querySchema, join, preQuery, cx);
                    reader = new JDBCJoiningFeatureReader(ps, cx, this, querySchema, join, query);
                } else {
                    // build up a statement for the content
                    String sql = getDataStore().selectJoinSQL(querySchema, join, preQuery);
                    getDataStore().getLogger().fine(sql);

                    reader = new JDBCJoiningFeatureReader(sql, cx, this, querySchema, join, query);
                }

                // check for post filters
                if (join.hasPostFilters()) {
                    reader = new JDBCJoiningFilteringFeatureReader(reader, join);
                    // TODO: retyping
                }
            }
        } catch (Throwable e) { // NOSONAR
            // close the connection
            getDataStore().closeSafe(cx);
            // safely rethrow
            if (e instanceof Error) {
                throw (Error) e;
            } else {
                throw (IOException) new IOException().initCause(e);
            }
        }

        // if post filter, wrap it
        if (postFilterRequired) {
            reader = new FilteringFeatureReader<>(reader, postFilter);
            if (!returnedSchema.equals(querySchema)) {
                reader = new ReTypeFeatureReader(reader, returnedSchema);
            }

            // offset
            int offset = query.getStartIndex() != null ? query.getStartIndex() : 0;
            if (offset > 0) {
                // skip the first n records
                for (int i = 0; i < offset && reader.hasNext(); i++) {
                    reader.next();
                }
            }

            // max feature limit
            if (query.getMaxFeatures() >= 0 && query.getMaxFeatures() < Integer.MAX_VALUE) {
                reader = new MaxFeatureReader<>(reader, query.getMaxFeatures());
            }
        }

        return reader;
    }

}

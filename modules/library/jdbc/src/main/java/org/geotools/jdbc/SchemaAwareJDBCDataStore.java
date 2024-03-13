package org.geotools.jdbc;

import static org.geotools.jdbc.VirtualTable.setKeepWhereClausePlaceHolderHint;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import org.geotools.api.data.Query;
import org.geotools.api.data.Transaction;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.feature.type.GeometryDescriptor;
import org.geotools.api.feature.type.Name;
import org.geotools.api.filter.Filter;
import org.geotools.api.filter.expression.Expression;
import org.geotools.data.jdbc.FilterToSQL;
import org.geotools.data.jdbc.FilterToSQLException;
import org.geotools.data.store.ContentEntry;
import org.geotools.data.store.ContentFeatureSource;
import org.geotools.feature.NameImpl;
import org.geotools.feature.visitor.LimitingVisitor;
import org.geotools.feature.visitor.UniqueCountVisitor;
import org.geotools.util.factory.Hints;

public class SchemaAwareJDBCDataStore extends JDBCDataStore {
    @Override
    protected List<Name> createTypeNames() throws IOException {
        Connection cx = createConnection();
        List<Name> typeNames = new ArrayList<>();

        try {
            DatabaseMetaData metaData = cx.getMetaData();
            Set<String> availableTableTypes = new HashSet<>();

            ResultSet tableTypes = null;
            try {
                tableTypes = metaData.getTableTypes();
                while (tableTypes.next()) {
                    availableTableTypes.add(tableTypes.getString("TABLE_TYPE"));
                }
            } finally {
                closeSafe(tableTypes);
            }
            Set<String> queryTypes = new HashSet<>();
            for (String desiredTableType : dialect.getDesiredTablesType()) {
                if (availableTableTypes.contains(desiredTableType)) {
                    queryTypes.add(desiredTableType);
                }
            }
            ResultSet tables =
                    metaData.getTables(
                            null,
                            escapeNamePattern(metaData, databaseSchema),
                            "%",
                            queryTypes.toArray(new String[0]));
            try {
                if (fetchSize > 1) {
                    tables.setFetchSize(fetchSize);
                }
                while (tables.next()) {
                    String schemaName = tables.getString("TABLE_SCHEM");
                    String tableName = tables.getString("TABLE_NAME");

                    // use the dialect to filter
                    if (!dialect.includeTable(schemaName, tableName, cx)) {
                        continue;
                    }

                    typeNames.add(new NameImpl(schemaName, ".", tableName));
                }
            } finally {
                closeSafe(tables);
            }
        } catch (SQLException e) {
            throw (IOException)
                    new IOException("Error occurred getting table name list.").initCause(e);
        } finally {
            closeSafe(cx);
        }

        for (String virtualTable : virtualTables.keySet()) {
            typeNames.add(new NameImpl(namespaceURI, virtualTable));
        }
        return typeNames;
    }

    @Override
    public String[] getTypeNames() throws IOException {
        List<Name> typeNames = createTypeNames();
        String[] names = new String[typeNames.size()];

        for (int i = 0; i < typeNames.size(); i++) {
            Name typeName = typeNames.get(i);
            names[i] =
                    typeName.getNamespaceURI() + typeName.getSeparator() + typeName.getLocalPart();
        }

        return names;
    }

    /** We need to reparse the string given as argument */
    @Override
    public ContentFeatureSource getFeatureSource(String typeName) throws IOException {
        String[] qualifiedTypeName = typeName.split("\\.");
        if (qualifiedTypeName.length == 2) {
            return getFeatureSource(
                    new NameImpl(qualifiedTypeName[0], ".", qualifiedTypeName[1]),
                    Transaction.AUTO_COMMIT);
        }
        return getFeatureSource(
                new NameImpl(qualifiedTypeName[0]),
                Transaction.AUTO_COMMIT);
    }

    @Override
    public ContentFeatureSource getFeatureSource(Name typeName, Transaction tx) throws IOException {

        ContentEntry entry = ensureEntry(typeName);

        ContentFeatureSource featureSource = createFeatureSource(entry);
        featureSource.setTransaction(tx);

        return featureSource;
    }

    @Override
    protected ContentFeatureSource createFeatureSource(ContentEntry entry) throws IOException {
        // grab the schema, it carries a flag telling us if the feature type is read only
        SimpleFeatureType schema = entry.getState(Transaction.AUTO_COMMIT).getFeatureType();
        if (schema == null) {
            // if the schema still haven't been computed, force its computation so
            // that we can decide if the feature type is read only
            JDBCFeatureSource fs = new SchemaAwareJDBCFeatureSource(entry, null);
            schema = fs.buildFeatureType();
            entry.getState(Transaction.AUTO_COMMIT).setFeatureType(schema);
        }
        // TODO ????
        Object readOnlyMarker = schema.getUserData().get(JDBC_READ_ONLY);
        if (Boolean.TRUE.equals(readOnlyMarker)) {
            return new SchemaAwareJDBCFeatureSource(entry, null);
        }
        return new JDBCFeatureStore(entry, null);
    }

    List<FilterToSQL> doSelectAggregateSQL(
            String function,
            List<Expression> expressions,
            List<Expression> groupByExpressions,
            SimpleFeatureType featureType,
            Query query,
            LimitingVisitor visitor,
            StringBuffer sql)
            throws SQLException, IOException {
        JoinInfo join =
                !query.getJoins().isEmpty() ? JoinInfo.create(query, featureType, this) : null;

        List<FilterToSQL> toSQL = new ArrayList<>();
        boolean queryLimitOffset = checkLimitOffset(query.getStartIndex(), query.getMaxFeatures());
        boolean visitorLimitOffset =
                visitor == null ? false : visitor.hasLimits() && dialect.isLimitOffsetSupported();
        // grouping over expressions is complex, as we need
        boolean groupByComplexExpressions = hasComplexExpressions(groupByExpressions);
        if (queryLimitOffset && !visitorLimitOffset && !groupByComplexExpressions) {
            if (join != null) {
                // don't select * to avoid ambigous result set
                sql.append("SELECT ");
                dialect.encodeColumnName(null, join.getPrimaryAlias(), sql);
                sql.append(".* FROM ");
            } else {
                sql.append("SELECT * FROM ");
            }
        } else {
            sql.append("SELECT ");
            FilterToSQL filterToSQL = getFilterToSQL(featureType);
            if (groupByExpressions != null && !groupByExpressions.isEmpty()) {
                try {
                    // we encode all the group by attributes as columns names
                    int i = 1;
                    for (Expression expression : groupByExpressions) {
                        GeometryDescriptor gd = getGeometryDescriptor(featureType, expression);
                        if (gd != null) {
                            dialect.encodeGeometryColumn(
                                    gd, null, getDescriptorSRID(gd), null, sql);
                        } else {
                            sql.append(filterToSQL.encodeToString(expression));
                        }
                        // if we are using complex group by, we have to given them an alias
                        if (groupByComplexExpressions) {
                            sql.append(" as ").append(getAggregateExpressionAlias(i++));
                        }
                        sql.append(", ");
                    }
                } catch (FilterToSQLException e) {
                    throw new RuntimeException("Failed to encode group by expressions", e);
                }
            }

            if (groupByComplexExpressions) {
                // if encoding a sub-query, the source of the aggregation function must
                // also be given an alias (we could use * too, but there is a risk of conflicts)
                if (expressions != null) {
                    int size = expressions.size();
                    for (int i = 0; i < size; i++) {
                        Expression expr = expressions.get(i);
                        try {
                            String colName = filterToSQL.encodeToString(expr);
                            sql.append(colName);
                            sql.append(" as gt_agg_src_").append(colName.replaceAll("\"", ""));
                            if (i < size - 1) sql.append(",");
                        } catch (FilterToSQLException e) {
                            throw new RuntimeException("Failed to encode group by expressions", e);
                        }
                    }
                } else {
                    // remove the last comma and space
                    sql.setLength(sql.length() - 2);
                }
            } else {
                encodeFunction(function, expressions, sql, filterToSQL);
            }
            toSQL.add(filterToSQL);
            sql.append(" FROM ");
        }

        if (join != null) {
            // TODO: to be patched ?
            encodeTableJoin(featureType, join, query, sql);
        } else {
            //
            encodeTableName(featureType.getName(), sql, setKeepWhereClausePlaceHolderHint(query));
        }

        if (join != null) {
            toSQL.addAll(encodeWhereJoin(featureType, join, sql));
        } else {
            Filter filter = query.getFilter();
            if (filter != null && !Filter.INCLUDE.equals(filter)) {
                sql.append(" WHERE ");
                toSQL.add(filter(featureType, filter, sql));
            }
        }
        if (dialect.isAggregatedSortSupported(function)) {
            sort(featureType, query.getSortBy(), null, sql);
        }

        // apply limits
        if (visitorLimitOffset) {
            applyLimitOffset(sql, visitor.getStartIndex(), visitor.getMaxFeatures());
        } else if (queryLimitOffset) {
            applyLimitOffset(sql, query.getStartIndex(), query.getMaxFeatures());
        }
        boolean isUniqueCount = visitor instanceof UniqueCountVisitor;
        // if the limits were in query or there is a group by with complex expressions
        // or there is a UniqueCountVisitor
        // we need to roll what was built so far in a sub-query
        if (queryLimitOffset || groupByComplexExpressions || isUniqueCount) {
            StringBuffer sql2 = new StringBuffer("SELECT ");
            try {
                if (groupByExpressions != null && !groupByExpressions.isEmpty()) {
                    FilterToSQL filterToSQL = getFilterToSQL(featureType);
                    int i = 1;
                    for (Expression expression : groupByExpressions) {
                        if (groupByComplexExpressions) {
                            sql2.append(getAggregateExpressionAlias(i++));
                        } else {
                            sql2.append(filterToSQL.encodeToString(expression));
                        }
                        sql2.append(",");
                    }
                    toSQL.add(filterToSQL);
                }
            } catch (FilterToSQLException e) {
                throw new RuntimeException("Failed to encode group by expressions", e);
            }
            FilterToSQL filterToSQL = getFilterToSQL(featureType);
            boolean countQuery =
                    isUniqueCount || (groupByComplexExpressions && "count".equals(function));
            if (countQuery) sql2.append("count(*)");
            else if (groupByComplexExpressions) {
                sql2.append(function).append("(");
                int size = expressions.size();
                for (int i = 0; i < size; i++) {
                    Expression expr = expressions.get(i);
                    try {
                        String aliasSuffix = filterToSQL.encodeToString(expr).replaceAll("\"", "");
                        sql2.append("gt_agg_src_").append(aliasSuffix);
                        if (i < size - 1) {
                            sql2.append(",");
                        }
                    } catch (FilterToSQLException e) {
                        throw new RuntimeException("Failed to encode column alias in group by.", e);
                    }
                }
                sql2.append(")");
            } else {
                encodeFunction(function, expressions, sql2, filterToSQL);
            }
            toSQL.add(filterToSQL);
            sql2.append(" AS gt_result_");
            sql2.append(" FROM (");
            sql.insert(0, sql2);
            sql.append(") gt_limited_");
        }

        FilterToSQL filterToSQL = getFilterToSQL(featureType);
        encodeGroupByStatement(groupByExpressions, sql, filterToSQL, groupByComplexExpressions);
        toSQL.add(filterToSQL);

        // add search hints if the dialect supports them
        applySearchHints(featureType, query, sql);

        return toSQL;
    }

    protected PrimaryKey getPrimaryKey(ContentEntry entry) throws IOException {
        JDBCState state = (JDBCState) entry.getState(Transaction.AUTO_COMMIT);

        if (state.getPrimaryKey() == null) {
            synchronized (this) {
                if (state.getPrimaryKey() == null) {
                    // get metadata from database
                    Connection cx = createConnection();

                    try {
                        PrimaryKey pkey = null;
                        String tableName = tableNameFromName(entry.getName());
                        if (virtualTables.containsKey(tableName)) {
                            VirtualTable vt = virtualTables.get(tableName);
                            if (vt.getPrimaryKeyColumns().size() == 0) {
                                pkey = new NullPrimaryKey(tableName);
                            } else {
                                List<ColumnMetadata> metas =
                                        JDBCFeatureSource.getColumnMetadata(cx, vt, dialect, this);

                                List<PrimaryKeyColumn> kcols = new ArrayList<>();
                                for (String pkName : vt.getPrimaryKeyColumns()) {
                                    // look for the pk type
                                    Class binding = null;
                                    for (ColumnMetadata meta : metas) {
                                        if (meta.name.equals(pkName)) {
                                            binding = meta.binding;
                                        }
                                    }

                                    // we build a pk without type, the JDBCFeatureStore will do this
                                    // for us while building the primary key
                                    kcols.add(new NonIncrementingPrimaryKeyColumn(pkName, binding));
                                }
                                pkey = new PrimaryKey(tableName, kcols);
                            }
                        } else {
                            try {
                                pkey =
                                        primaryKeyFinder.getPrimaryKey(
                                                this, databaseSchema, tableName, cx);
                            } catch (SQLException e) {
                                LOGGER.log(
                                        Level.WARNING,
                                        "Failure occurred while looking up the primary key with "
                                                + "finder: "
                                                + primaryKeyFinder,
                                        e);
                            }

                            if (pkey == null) {
                                String msg =
                                        "No primary key or unique index found for "
                                                + tableName
                                                + ".";
                                LOGGER.info(msg);

                                pkey = new NullPrimaryKey(tableName);
                            }
                        }

                        state.setPrimaryKey(pkey);
                    } catch (SQLException e) {
                        String msg = "Error looking up primary key";
                        throw (IOException) new IOException(msg).initCause(e);
                    } finally {
                        closeSafe(cx);
                    }
                }
            }
        }

        return state.getPrimaryKey();
    }

    protected <F extends FilterToSQL> F initializeFilterToSQL(
            F toSQL, final SimpleFeatureType featureType) {
        toSQL.setSqlNameEscape(dialect.getNameEscape());

        if (featureType != null) {
            // set up a fid mapper
            // TODO: remove this
            final PrimaryKey key;

            try {
                key = getPrimaryKey(featureType);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            toSQL.setFeatureType(featureType);
            toSQL.setPrimaryKey(key);
            String schema = featureType.getName().getNamespaceURI();
            toSQL.setDatabaseSchema(schema);
        }

        return toSQL;
    }

    public void encodeTableName(Name name, StringBuffer sql, Hints hints) throws SQLException {
        encodeAliasedTableName(name, sql, hints, null);
    }

    public String tableNameFromName(Name name) {
        StringBuffer buf = new StringBuffer();
        if (name.getNamespaceURI() != null) {
            dialect.encodeSchemaName(name.getNamespaceURI(), buf);
            buf.append(".");
        }
        dialect.encodeTableName(name.getLocalPart(), buf);
        return buf.toString();
    }

    public void encodeAliasedTableName(Name name, StringBuffer sql, Hints hints, String alias)
            throws SQLException {
        VirtualTable vtDefinition = virtualTables.get(name.toString());
        if (vtDefinition != null) {
            sql.append("(").append(vtDefinition.expandParameters(hints)).append(")");
            if (alias == null) {
                alias = "vtable";
            }
            dialect.encodeTableAlias(alias, sql);
        } else {
            if (name.getNamespaceURI() != null) {
                dialect.encodeSchemaName(name.getNamespaceURI(), sql);
                sql.append(".");
            }

            dialect.encodeTableName(name.getLocalPart(), sql);
            if (alias != null) {
                dialect.encodeTableAlias(alias, sql);
            }
        }
    }
    /**
     * Generates a 'SELECT p1, p2, ... FROM ... WHERE ...' statement.
     *
     * @param featureType the feature type that the query must return (may contain less attributes
     *     than the native one)
     * @param query the query to be run. The type name and property will be ignored, as they are
     *     supposed to have been already embedded into the provided feature type
     */
    protected String selectSQL(SimpleFeatureType featureType, Query query)
            throws IOException, SQLException {
        StringBuffer sql = new StringBuffer();
        sql.append("SELECT ");

        // column names
        selectColumns(featureType, null, query, sql);
        sql.setLength(sql.length() - 1);
        dialect.encodePostSelect(featureType, sql);

        // from
        sql.append(" FROM ");
        encodeTableName(featureType.getName(), sql, setKeepWhereClausePlaceHolderHint(query));

        // filtering
        Filter filter = query.getFilter();
        if (filter != null && !Filter.INCLUDE.equals(filter)) {
            sql.append(" WHERE ");
            // encode filter
            filter(featureType, filter, sql);
        }

        // sorting
        sort(featureType, query.getSortBy(), null, sql);

        // encode limit/offset, if necessary
        applyLimitOffset(sql, query.getStartIndex(), query.getMaxFeatures());

        // add search hints if the dialect supports them
        applySearchHints(featureType, query, sql);

        return sql.toString();
    }
}

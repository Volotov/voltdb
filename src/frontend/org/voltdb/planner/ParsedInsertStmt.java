/* This file is part of VoltDB.
 * Copyright (C) 2008-2014 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.planner;

import java.util.LinkedHashMap;
import java.util.Map.Entry;

import org.hsqldb_voltpatches.VoltXMLElement;
import org.voltdb.VoltType;
import org.voltdb.catalog.Column;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Table;
import org.voltdb.expressions.AbstractExpression;
import org.voltdb.expressions.ConstantValueExpression;
import org.voltdb.expressions.FunctionExpression;

/**
 *
 *
 */
public class ParsedInsertStmt extends AbstractParsedStmt {

    /**
     * A hash of the columns that were provided in the insert stmt,
     * and the corresponding values that were provided.  It is a
     * linked hash map so we retain the order in which the user
     * specified the columns.
     */
    public LinkedHashMap<Column, AbstractExpression> m_columns = new LinkedHashMap<Column, AbstractExpression>();
    private ParsedSelectStmt m_subselect;

    /**
    * Class constructor
    * @param paramValues
    * @param db
    */
    public ParsedInsertStmt(String[] paramValues, Database db) {
        super(paramValues, db);
    }

    @Override
    void parse(VoltXMLElement stmtNode) {
        // A simple INSERT ... VALUES statement (all that's supported initially) has no underlying
        // table scans, so the table list should actually be empty until the statement's target
        // table is inserted, below.
        assert(m_tableList.isEmpty());

        String tableName = stmtNode.attributes.get("table");
        Table table = getTableFromDB(tableName);

        m_tableList.add(table);

        for (VoltXMLElement node : stmtNode.children) {
            if (node.name.equalsIgnoreCase("columns")) {
                parseTargetColumns(node, table, m_columns);
            }
            else if (node.name.equalsIgnoreCase(SELECT_NODE_NAME)) {
                // TODO: When INSERT ... SELECT is supported, it's unclear whether the source tables need to be
                // reflected in the m_tableList -- that would likely be propagated here --
                // and how the target table would then be distinguished
                // (positionally? in a separate member?) and/or how soon thereafter the SELECT
                // clause will need to allow joins.
                m_subselect = (ParsedSelectStmt)parseSubquery(node);
            }
            else if (node.name.equalsIgnoreCase(UNION_NODE_NAME)) {
                throw new PlanningErrorException(
                        "INSERT INTO ... SELECT is not supported for UNION or other set operations.");
            }
            // else ... assert if there are unexpected child elements of the insert node?
        }

        if (m_subselect != null) {
            // Put other static checks here?  What belongs here and what belongs in PlanAssembler?
        }
    }

    @Override
    public String toString() {
        String retval = super.toString() + "\n";

        retval += "COLUMNS:\n";
        for (Entry<Column, AbstractExpression> col : m_columns.entrySet()) {
            retval += "\tColumn: " + col.getKey().getTypeName();
            if (col.getValue() != null) {
                retval += ": " + col.getValue().toString();
            }
            retval += "\n";
        }

        if (m_subselect != null) {
            retval += "SUBSELECT:\n";
            retval += m_subselect.toString();
        }
        return retval;
    }

    private AbstractExpression defaultValueToExpr(Column column) {
        AbstractExpression expr = null;

        boolean isConstantValue = true;
        if (column.getDefaulttype() == VoltType.TIMESTAMP.getValue()) {

            boolean isFunctionFormat = true;
            String timeValue = column.getDefaultvalue();
            try {
                Long.parseLong(timeValue);
                isFunctionFormat = false;
            } catch (NumberFormatException  e) {}
            if (isFunctionFormat) {
                try {
                    java.sql.Timestamp.valueOf(timeValue);
                    isFunctionFormat = false;
                } catch (IllegalArgumentException e) {}
            }

            if (isFunctionFormat) {
                String name = timeValue.split(":")[0];
                int id = Integer.parseInt(timeValue.split(":")[1]);

                FunctionExpression funcExpr = new FunctionExpression();
                funcExpr.setAttributes(name, name , id);

                funcExpr.setValueType(VoltType.TIMESTAMP);
                funcExpr.setValueSize(VoltType.TIMESTAMP.getMaxLengthInBytes());

                expr = funcExpr;
                isConstantValue = false;
            }
        }
        if (isConstantValue) {
            // Not Default sql function.
            ConstantValueExpression const_expr = new ConstantValueExpression();
            expr = const_expr;
            if (column.getDefaulttype() != 0) {
                const_expr.setValue(column.getDefaultvalue());
                const_expr.refineValueType(VoltType.get((byte) column.getDefaulttype()), column.getSize());
            }
            else {
                const_expr.setValue(null);
                const_expr.refineValueType(VoltType.get((byte) column.getType()), column.getSize());
            }
        }

        assert(expr != null);
        return expr;
    }

    public AbstractExpression getExpressionForPartitioning(Column column) {
        AbstractExpression expr = null;
        if (m_subselect != null) {
            // Currently insert into select is only supported for the SP/SP case.
            return null;
        }
        else {
            expr = m_columns.get(column);
            if (expr == null) {
                expr = defaultValueToExpr(column);
            }
        }

        assert(expr != null);
        return expr;
    }

    /**
     * @return the m_subselect
     */
    public AbstractParsedStmt getSubselect() {
        return m_subselect;
    }

}

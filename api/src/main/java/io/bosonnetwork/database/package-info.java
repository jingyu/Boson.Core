/*
 * Copyright (c) 2023 -      bosonnetwork.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

/**
 * Lightweight SQL helper layer for Boson modules built on the Vert.x reactive SQL client. It
 * provides safe query construction, connection/transaction helpers, and file-based schema
 * migration - without pulling in a heavyweight ORM.
 *
 * <h2>Safe query building</h2>
 * Only bound <em>values</em> are parameterized; SQL identifiers (column / parameter / schema names)
 * are interpolated, so they are validated through {@link io.bosonnetwork.database.SqlSafety} to
 * prevent injection. The builders compose Vert.x {@code SqlTemplate} fragments:
 * <ul>
 *   <li>{@link io.bosonnetwork.database.Filter} - {@code WHERE} clauses (eq/ne/lt/gt/like/in, plus
 *       {@code AND}/{@code OR} composition) with named bind parameters;</li>
 *   <li>{@link io.bosonnetwork.database.Ordering} - {@code ORDER BY} clauses;</li>
 *   <li>{@link io.bosonnetwork.database.Pagination} - {@code LIMIT}/{@code OFFSET} clauses;</li>
 *   <li>{@link io.bosonnetwork.database.CollectionParameter} - expands a collection into the
 *       placeholder tuple needed for an {@code IN (...)} predicate.</li>
 * </ul>
 *
 * <h2>Execution</h2>
 * {@link io.bosonnetwork.database.VertxDatabase} wraps a {@code SqlClient} (pool or single
 * connection) with {@code withConnection}/{@code withTransaction} helpers and small row-mapping
 * utilities. Per the project convention, use {@code withTransaction} for writes and
 * {@code withConnection} for reads.
 *
 * <h2>Migrations</h2>
 * {@link io.bosonnetwork.database.VersionedSchema} applies versioned
 * {@code <version>_<description>.sql} migration files transactionally, records them in a
 * {@code schema_versions} table, and verifies SHA-256 checksums to detect tampering. It targets
 * PostgreSQL and SQLite via the Vert.x SQL clients.
 *
 * <p>This package is {@link org.jspecify.annotations.NullMarked} - every type, parameter, return and
 * field is non-null by default; anything that may be {@code null} is explicitly
 * {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package io.bosonnetwork.database;

import org.jspecify.annotations.NullMarked;
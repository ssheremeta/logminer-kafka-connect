package com.github.thake.logminer.kafka.connect.initial

import com.github.thake.logminer.kafka.connect.*
import mu.KotlinLogging
import java.sql.Connection

private val logger = KotlinLogging.logger {}
class SelectSource(
    private val batchSize: Int,
    private val tablesToFetch: List<TableId>,
    private val schemaService: SchemaService,
    var lastOffset: SelectOffset?
) : Source<SelectOffset?> {

    var currentTableFetcher: TableFetcher? = null
    var continuePolling = true

    init {
        if (tablesToFetch.isEmpty()) {
            throw java.lang.IllegalArgumentException("List of tables to fetch is empty, can't do anything")
        }
    }

    override fun getOffset() = lastOffset

    override fun maybeStartQuery(db: Connection) {
        if (currentTableFetcher == null) {
            currentTableFetcher = TableFetcher(
                db,
                FetcherOffset(determineTableToFetch(), determineAsOfScn(db), lastOffset?.rowId),
                schemaService = schemaService
            )
        }
    }

    private fun determineTableToFetch(): TableId {
        return lastOffset?.table
            ?: tablesToFetch.first()

    }

    @Suppress("SqlResolve")
    private fun determineAsOfScn(conn: Connection): Long {
        return lastOffset?.scn ?: conn.prepareStatement("select CURRENT_SCN from V${'$'}DATABASE").use { stmt ->
            stmt.executeQuery().use {
                it.next()
                it.getLong(1)
            }
        }.also {
            logger.info { "Determined current scn of database as $it" }
        }
    }

    override fun poll(): List<PollResult> {
        var fetcher = currentTableFetcher ?: throw IllegalStateException("maybeStartQuery hasn't been called")
        val result = mutableListOf<PollResult>()
        while (result.size < batchSize && continuePolling) {
            val nextRecord = fetcher.poll()
            if (nextRecord != null) {
                lastOffset = nextRecord.offset as SelectOffset
                result.add(nextRecord)
            } else {
                //No new records from the current table. Close the fetcher and check the next table
                fetcher.close()
                val newIndex = tablesToFetch.indexOf(fetcher.fetcherOffset.table) + 1
                if (newIndex < tablesToFetch.size) {
                    fetcher = TableFetcher(
                        fetcher.conn,
                        FetcherOffset(tablesToFetch[newIndex], fetcher.fetcherOffset.asOfScn, null),
                        schemaService
                    )
                    currentTableFetcher = fetcher
                    //Exit the loop to return the current result set.
                    break
                } else {
                    //no more records to poll all tables polled
                    continuePolling = false
                }
            }
        }
        return result
    }

    override fun close() {
        currentTableFetcher?.close()
    }
}
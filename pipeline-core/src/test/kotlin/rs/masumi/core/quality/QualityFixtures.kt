package rs.masumi.core.quality

object QualityFixtures {
    val dependencies = QualityDependencies(typesettingRunArtifactKey = "a".repeat(64))

    fun job(): QualityJobRecord = QualityJobRecord(
        jobId = "quality-job",
        projectId = "project-1",
        runArtifactKey = "b".repeat(64),
        startedAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        dependencies = dependencies,
        pages = listOf(
            QualityJobPage(
                pageId = "c".repeat(64),
                pageOrder = 0,
                sourceSha256 = "c".repeat(64),
                typesettingPageArtifactKey = "d".repeat(64),
                pageArtifactKey = "e".repeat(64),
            ),
        ),
    )

    fun artifact(verdict: QualityPageVerdict = QualityPageVerdict.PASS): PageQualityArtifact {
        val issues = when (verdict) {
            QualityPageVerdict.PASS -> emptyList()
            QualityPageVerdict.PASS_WITH_WARNINGS -> listOf(
                QualityIssue(QualityIssueCode.REGION_PRESERVED, QualitySeverity.WARNING, "1".repeat(64)),
            )
            QualityPageVerdict.BLOCKED -> listOf(
                QualityIssue(QualityIssueCode.TYPESET_PIXELS_MISSING, QualitySeverity.BLOCKING, "1".repeat(64), 0),
            )
        }
        return PageQualityArtifact(
            pageId = "c".repeat(64),
            pageOrder = 0,
            sourceSha256 = "c".repeat(64),
            typesettingPageArtifactKey = "d".repeat(64),
            pageArtifactKey = "e".repeat(64),
            renderedImageSha256 = "f".repeat(64),
            dependencies = dependencies,
            actualChangedPixelCount = if (verdict == QualityPageVerdict.BLOCKED) 0 else 50,
            verifiedTypesetRegionCount = if (verdict == QualityPageVerdict.BLOCKED) 0 else 1,
            issues = issues,
            verdict = verdict,
        )
    }
}

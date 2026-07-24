package rs.masumi.core.typesetting

enum class TypesettingRepairPageAction { RENDER, REUSE_COMMITTED, CARRY_PRESERVED }

object TypesettingRepairPlanner {
    fun pageAction(
        reuseState: TypesettingPageState?,
        explicitlySelectedForRepair: Boolean,
    ): TypesettingRepairPageAction {
        if (reuseState == null || explicitlySelectedForRepair) return TypesettingRepairPageAction.RENDER
        return when (reuseState) {
            TypesettingPageState.COMMITTED -> TypesettingRepairPageAction.REUSE_COMMITTED
            TypesettingPageState.PRESERVED_CLEANED_PAGE -> TypesettingRepairPageAction.CARRY_PRESERVED
            TypesettingPageState.PENDING,
            TypesettingPageState.RUNNING,
            -> throw IllegalArgumentException("reuse run contains a non-terminal page")
        }
    }
}

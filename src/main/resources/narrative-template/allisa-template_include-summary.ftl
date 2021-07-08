<#include "narrative-and-time-row-info.ftl">
${'\r\n'}Total Worked Time: ${getTotalDuration(getTimeRows())?string.@duration}
<#if getTotalDuration(getTimeRows()) == getTotalDurationSecs() && getUser().getExperienceWeightingPercent() != 100>
Total Chargeable Time: ${(getTotalDurationSecsPerTag() * getUser().getExperienceWeightingPercent() / 100)?round?string.@duration}
${'\r\n'}The chargeable time has been weighed based on an experience factor of ${getUser().getExperienceWeightingPercent()}%.
<#else>
Total Chargeable Time: ${getTotalDurationSecsPerTag()?string.@duration}
</#if>
<#if getDurationSplitStrategy() == "DIVIDE_BETWEEN_TAGS" && (getTags()?size > 1)>
    ${'\r\n'}The above times have been split across ${getTags()?size} cases and are thus greater than the chargeable time in this case
</#if>
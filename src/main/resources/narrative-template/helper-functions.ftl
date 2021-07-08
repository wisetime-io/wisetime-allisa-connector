<#function getTotalDuration timeRows>
 <#local rowTotalDuration = 0?number>
 <#list timeRows as timeRow>
  <#local rowTotalDuration += timeRow.getDurationSecs()>
 </#list>
 <#return rowTotalDuration />
</#function>

<#function getTotalDurationSecsPerTag>
 <#local totalDurationSecs = getTotalDurationSecs()>
 <#if getDurationSplitStrategy() == "DIVIDE_BETWEEN_TAGS" && (getTags()?size > 1)>
  <#local totalDurationSecs /= getTags()?size>
 </#if>
 <#return totalDurationSecs />
</#function>

<#function getSegmentHourBlocks timeRows>
 <#local segmentHours = []>
 <#list timeRows as timeRow>
  <#local segmentHour = (timeRow.getActivityHour() % 100)?string["00"]>
  <#if !(segmentHours?seq_contains(segmentHour))>
   <#local segmentHours += [segmentHour]>
  </#if>
 </#list>
 <#return segmentHours />
</#function>

<#function sanitizeAppName appName>
 <#local newAppName = appName>
 <#if appName?starts_with("@_") && appName?ends_with("_@")>
  <#local newAppName = appName?substring(2, (appName?length - 2))>
 </#if>
 <#return newAppName />
</#function>

<#function sanitizeWindowTitle winTitle>
 <#if winTitle == "@_empty_@" >
  <#return "No window title available" />
 </#if>
 <#return winTitle />
</#function>
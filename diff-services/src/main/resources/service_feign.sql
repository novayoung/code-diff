select "service", "feign", "method", "uri", "path" from (select "m3_attribute_value" as "service", "m1_class_name" as "feign", "m1_full_method" as "method",
REPLACE(REPLACE(REPLACE("m1_attribute_value", '[', ''), ']', ''), '"', '') as "uri"
from
(
select 
"full_method" as "m1_full_method",
REGEXP_REPLACE(ma."full_method", '^(.*?):.*$', '$1')  as "m1_class_name", 
ma."attribute_value" as "m1_attribute_value"
from "jacg"."jacg_method_annotation_{app}" as ma where "annotation_name" like '%Mapping' and "attribute_name" = 'value' and "method_hash" in (
SELECT distinct call."callee_method_hash" FROM "jacg"."jacg_method_call_{app}" as call where call."callee_method_hash" in (
  select md."method_hash" from "jacg"."jacg_method_info_{app}" as md where REGEXP_REPLACE(md."full_method", '^(.*?):.*$', '$1')  in (
      SELECT distinct "class_name" FROM 
    "jacg"."jacg_class_annotation_{app}" cls
        where "annotation_name" like '%FeignClient' and ("attribute_name" = 'name' or "attribute_name" = 'value')
  )
)
)) m1, 

(select "class_name" as "m3_class_name", "attribute_value" as "m3_attribute_value" from "jacg"."jacg_class_annotation_{app}" where 
"annotation_name" like '%FeignClient' and ("attribute_name" = 'name' or "attribute_name" = 'value')) m3

where m3."m3_class_name" = m1."m1_class_name"
) m1m3 left join 

(select "attribute_value" as "path", "class_name" from "jacg"."jacg_class_annotation_{app}" where "class_name" in (
SELECT "class_name" FROM "jacg"."jacg_class_annotation_{app}" where  "annotation_name" like '%FeignClient' and "attribute_name" = 'name'
) and "annotation_name" like '%Mapping' and "attribute_name" = 'value') m2 on m2."class_name" = m1m3."feign"
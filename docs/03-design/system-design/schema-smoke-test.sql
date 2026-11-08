-- =============================================================================
-- KaiXu schema smoke test (design baseline v1.0)
-- Run AFTER schema.sql on an empty PostgreSQL 16 database:
--   psql -d kaixu -v ON_ERROR_STOP=1 -f schema.sql
--   psql -d kaixu -v ON_ERROR_STOP=1 -f schema-smoke-test.sql
-- Expected: eight NOTICE "OK ..." lines and final "SMOKE OK".
-- =============================================================================

\set ON_ERROR_STOP on

insert into app_user (id,email,password_hash,display_name) values
 ('00000000-0000-0000-0000-000000000001','a@example.com','hash','A'),
 ('00000000-0000-0000-0000-000000000002','b@example.com','hash','B');

insert into workspace (id,owner_user_id,kind,name,created_by) values
 ('10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','PROJECT','P1','00000000-0000-0000-0000-000000000001'),
 ('10000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','PROJECT','P2','00000000-0000-0000-0000-000000000001');

insert into workspace_member (workspace_id,user_id,role) values
 ('10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','OWNER');

insert into task (id,workspace_id,created_by,title) values
 ('20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','T1'),
 ('20000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','T2');

insert into task_stage (id,task_id,name,sort_order) values
 ('30000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001','S1',1),
 ('30000000-0000-0000-0000-000000000002','20000000-0000-0000-0000-000000000002','S2',1);

insert into resource (id,workspace_id,name,kind,url,created_by) values
 ('40000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','R1','LINK','https://example.com','00000000-0000-0000-0000-000000000001');

insert into source_connection (id,user_id,source_type,display_name) values
 ('50000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','WECHAT','wechat');
insert into source_record (id,user_id,source_connection_id,source_type,external_record_id) values
 ('60000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','50000000-0000-0000-0000-000000000001','WECHAT','r1');
insert into intake_candidate (id,user_id,source_record_id,status,title,accepted_at) values
 ('70000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','60000000-0000-0000-0000-000000000001','ACCEPTED','c1',now());

insert into focus_session (id,client_session_id,user_id,target_minutes) values
 ('80000000-0000-0000-0000-000000000001','90000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001',60);
insert into focus_segment (session_id,client_segment_id,kind,started_at,ends_at) values
 ('80000000-0000-0000-0000-000000000001','90000000-0000-0000-0000-000000000002','FOREGROUND','2026-11-02 19:00+08','2026-11-02 19:30+08');

-- 1 duplicate active owner
do $$ begin
  begin
    insert into workspace_member (workspace_id,user_id,role) values
      ('10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','OWNER');
    raise exception 'EXPECTED duplicate owner failure did not happen';
  exception when unique_violation then raise notice 'OK duplicate active owner blocked';
  end;
end $$;

-- 2 cross-workspace link
do $$ begin
  begin
    insert into resource_task_link (task_id,resource_id,created_by) values
      ('20000000-0000-0000-0000-000000000002','40000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001');
    raise exception 'EXPECTED cross-workspace link failure did not happen';
  exception when check_violation then raise notice 'OK cross-workspace resource link blocked';
  end;
end $$;

-- 3 task workspace immutable
do $$ begin
  begin
    update task set workspace_id='10000000-0000-0000-0000-000000000002' where id='20000000-0000-0000-0000-000000000001';
    raise exception 'EXPECTED task workspace immutability failure did not happen';
  exception when check_violation then raise notice 'OK task workspace change blocked';
  end;
end $$;

-- 4 one accepted candidate per source record
do $$ begin
  begin
    insert into intake_candidate (id,user_id,source_record_id,status,title,accepted_at) values
      ('70000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','60000000-0000-0000-0000-000000000001','ACCEPTED','c2',now());
    raise exception 'EXPECTED duplicate accepted source failure did not happen';
  exception when unique_violation then raise notice 'OK duplicate accepted source blocked';
  end;
end $$;

-- 5 one active focus session per user
do $$ begin
  begin
    insert into focus_session (id,client_session_id,user_id,target_minutes) values
      ('80000000-0000-0000-0000-000000000002','90000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000001',30);
    raise exception 'EXPECTED duplicate focus session failure did not happen';
  exception when unique_violation then raise notice 'OK concurrent focus session blocked';
  end;
end $$;

-- 6 overlapping focus segment
do $$ begin
  begin
    insert into focus_segment (session_id,client_segment_id,kind,started_at,ends_at) values
      ('80000000-0000-0000-0000-000000000001','90000000-0000-0000-0000-000000000004','BACKGROUND','2026-11-02 19:15+08','2026-11-02 19:45+08');
    raise exception 'EXPECTED overlapping segment failure did not happen';
  exception when exclusion_violation then raise notice 'OK overlapping focus segment blocked';
  end;
end $$;

-- 7 resource current attachment must belong to resource
do $$ begin
  begin
    insert into resource (id,workspace_id,name,kind,created_by) values
      ('40000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000001','R2','ATTACHMENT','00000000-0000-0000-0000-000000000001');
    insert into resource_attachment (id,resource_id,version_no,object_key,file_name,content_type,size_bytes,sha256,uploader_user_id,is_current) values
      ('a0000000-0000-0000-0000-000000000001','40000000-0000-0000-0000-000000000001',1,'k1','f.pdf','application/pdf',10,decode(repeat('ab',32),'hex'),'00000000-0000-0000-0000-000000000001',true);
    update resource set current_attachment_id='a0000000-0000-0000-0000-000000000001' where id='40000000-0000-0000-0000-000000000002';
    raise exception 'EXPECTED resource attachment mismatch failure did not happen';
  exception when check_violation then raise notice 'OK resource attachment mismatch blocked';
  end;
end $$;

-- 8 stage dependency across tasks
do $$ begin
  begin
    insert into task_stage_dependency (task_id,predecessor_stage_id,successor_stage_id) values
      ('20000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000002');
    raise exception 'EXPECTED cross-task stage dependency failure did not happen';
  exception when check_violation then raise notice 'OK cross-task stage dependency blocked';
  end;
end $$;

select 'SMOKE OK' as result;

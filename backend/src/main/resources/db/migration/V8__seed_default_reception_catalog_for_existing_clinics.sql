-- Backfill the default reception catalog for clinics created before this migration.
WITH defaults(name) AS (
    VALUES
      ('RAIO X'), ('COLETA / LABORATÓRIO'), ('AUDIOMETRIA'), ('EXAMES COMPLEMENTARES'),
      ('CONSULTA MÉDICA'), ('COLETA DE PCCU'), ('PROCEDIMENTOS'), ('TRIAGEM'),
      ('ULTRASSOM'), ('FISIOTERAPIA')
)
INSERT INTO clinic_rooms (clinic_id, name)
SELECT c.id, d.name
  FROM clinics c CROSS JOIN defaults d
 WHERE NOT EXISTS (
    SELECT 1 FROM clinic_rooms r
     WHERE r.clinic_id=c.id AND lower(r.name)=lower(d.name)
 );

WITH defaults(name, sort_order) AS (
    VALUES
      ('Admissional',0), ('Demissional',1), ('Periódico',2), ('Retorno',3),
      ('Mudança de riscos',4), ('Particular',5), ('Pontual',6),
      ('Consulta Ocupacional',7), ('Concurso',8)
)
INSERT INTO service_types (clinic_id, name, sort_order)
SELECT c.id, d.name, d.sort_order
  FROM clinics c CROSS JOIN defaults d
 WHERE NOT EXISTS (
    SELECT 1 FROM service_types st
     WHERE st.clinic_id=c.id AND lower(st.name)=lower(d.name)
 );

WITH exam_defaults(sort_order, category, name, room_name) AS (
    VALUES
      (0,'RAIO_X','Tórax PA','RAIO X'),
      (1,'RAIO_X','Tórax Perfil','RAIO X'),
      (2,'RAIO_X','Tórax OIT','RAIO X'),
      (3,'RAIO_X','Tórax AP','RAIO X'),
      (4,'RAIO_X','Coluna Lombar','RAIO X'),
      (5,'RAIO_X','Coluna Lombo Sacra','RAIO X'),
      (6,'RAIO_X','Coluna Total','RAIO X'),
      (7,'RAIO_X','Coluna Cervical','RAIO X'),
      (8,'RAIO_X','Coluna Torácica','RAIO X'),
      (9,'RAIO_X','Coluna Dorsal','RAIO X'),
      (10,'RAIO_X','Ombros','RAIO X'),
      (11,'RAIO_X','Ombro D','RAIO X'),
      (12,'RAIO_X','Ombro E','RAIO X'),
      (13,'RAIO_X','Mãos','RAIO X'),
      (14,'RAIO_X','Mão D','RAIO X'),
      (15,'RAIO_X','Mão E','RAIO X'),
      (16,'RAIO_X','Joelhos','RAIO X'),
      (17,'RAIO_X','Joelhos D','RAIO X'),
      (18,'RAIO_X','Joelhos E','RAIO X'),
      (19,'RAIO_X','Punhos','RAIO X'),
      (20,'RAIO_X','Punhos D','RAIO X'),
      (21,'RAIO_X','Punhos E','RAIO X'),
      (22,'LABORATORIO','Coleta de sangue','COLETA / LABORATÓRIO'),
      (23,'LABORATORIO','Hemograma C.','COLETA / LABORATÓRIO'),
      (24,'LABORATORIO','Glicose','COLETA / LABORATÓRIO'),
      (25,'LABORATORIO','Creatinina','COLETA / LABORATÓRIO'),
      (26,'LABORATORIO','Tipagem S.','COLETA / LABORATÓRIO'),
      (27,'LABORATORIO','Beta','COLETA / LABORATÓRIO'),
      (28,'LABORATORIO','Colesterol','COLETA / LABORATÓRIO'),
      (29,'LABORATORIO','Colinesterase','COLETA / LABORATÓRIO'),
      (30,'LABORATORIO','Cultura F','COLETA / LABORATÓRIO'),
      (31,'LABORATORIO','EPF','COLETA / LABORATÓRIO'),
      (32,'LABORATORIO','EAS','COLETA / LABORATÓRIO'),
      (33,'LABORATORIO','Acetona','COLETA / LABORATÓRIO'),
      (34,'LABORATORIO','Teste Ergonométrico','COLETA / LABORATÓRIO'),
      (35,'LABORATORIO','Acetona na Urina','COLETA / LABORATÓRIO'),
      (36,'LABORATORIO','Ácido Mandélico','COLETA / LABORATÓRIO'),
      (37,'LABORATORIO','Hemoglobina Glicada','COLETA / LABORATÓRIO'),
      (38,'LABORATORIO','Lipidograma','COLETA / LABORATÓRIO'),
      (39,'LABORATORIO','Chumbo na Urina','COLETA / LABORATÓRIO'),
      (40,'LABORATORIO','Micológico','COLETA / LABORATÓRIO'),
      (41,'LABORATORIO','Toxicológico','COLETA / LABORATÓRIO'),
      (42,'LABORATORIO','Triglicerídeos','COLETA / LABORATÓRIO'),
      (43,'LABORATORIO','TGO','COLETA / LABORATÓRIO'),
      (44,'LABORATORIO','TGP','COLETA / LABORATÓRIO'),
      (45,'LABORATORIO','Chumbo sanguíneo','COLETA / LABORATÓRIO'),
      (46,'LABORATORIO','Hepatite HBsAg','COLETA / LABORATÓRIO'),
      (47,'LABORATORIO','Hepatite Anti-HBs','COLETA / LABORATÓRIO'),
      (48,'LABORATORIO','Hepatite Anti-HCV','COLETA / LABORATÓRIO'),
      (49,'LABORATORIO','Hepatite B - ANTI-HBC TOTAL','COLETA / LABORATÓRIO'),
      (50,'LABORATORIO','Metil etil cetona','COLETA / LABORATÓRIO'),
      (51,'LABORATORIO','Ácido metil-hipúrico','COLETA / LABORATÓRIO'),
      (52,'LABORATORIO','Ácido hipúrico','COLETA / LABORATÓRIO'),
      (53,'LABORATORIO','Ácido delta-aminolevulínico','COLETA / LABORATÓRIO'),
      (54,'AUDIOMETRIA','Audiometria','AUDIOMETRIA'),
      (55,'AUDIOMETRIA','Audiometria Tonal','AUDIOMETRIA'),
      (56,'AUDIOMETRIA','Audiometria Vocal','AUDIOMETRIA'),
      (57,'AUDIOMETRIA','Audiometria Ocupacional','AUDIOMETRIA'),
      (58,'AUDIOMETRIA','Audiometria Concurso','AUDIOMETRIA'),
      (59,'AUDIOMETRIA','Teste Otoneurológico','AUDIOMETRIA'),
      (60,'AUDIOMETRIA','Teste Vestibular','AUDIOMETRIA'),
      (61,'COMPLEMENTAR','EEG','EXAMES COMPLEMENTARES'),
      (62,'COMPLEMENTAR','ECG','EXAMES COMPLEMENTARES'),
      (63,'COMPLEMENTAR','Espirometria','EXAMES COMPLEMENTARES'),
      (64,'COMPLEMENTAR','Acuidade','EXAMES COMPLEMENTARES'),
      (65,'COMPLEMENTAR','Acuidade com percepção de cores','EXAMES COMPLEMENTARES'),
      (66,'COMPLEMENTAR','Av Psico','EXAMES COMPLEMENTARES'),
      (67,'COMPLEMENTAR','Mapa 24h','EXAMES COMPLEMENTARES'),
      (68,'COMPLEMENTAR','Holter 24h','EXAMES COMPLEMENTARES'),
      (69,'COMPLEMENTAR','Risco Cirúrgico','EXAMES COMPLEMENTARES')
)
INSERT INTO exam_catalog (clinic_id, category, name, room_id, sort_order)
SELECT c.id, d.category, d.name, r.id, d.sort_order
  FROM clinics c
 CROSS JOIN exam_defaults d
  JOIN clinic_rooms r ON r.clinic_id=c.id AND lower(r.name)=lower(d.room_name)
 WHERE NOT EXISTS (
    SELECT 1 FROM exam_catalog e
     WHERE e.clinic_id=c.id AND e.category=d.category AND lower(e.name)=lower(d.name)
 );

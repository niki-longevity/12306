-- 武汉→广州南 (京广线)
INSERT IGNORE INTO train_template (train_code, line_code, business_carriage, first_class_carriage, second_class_carriage) VALUES
('G1001', 'JG-WG', 1, 2, 8);
INSERT IGNORE INTO train_template_stopover (train_code, station_name, station_index, in_time, out_time, mileage) VALUES
('G1001', '武汉站',    1, NULL, '08:00', 0),
('G1001', '长沙南站',  2, '09:20', '09:25', 362),
('G1001', '衡阳东站',  3, '10:00', '10:03', 510),
('G1001', '郴州西站',  4, '10:38', '10:41', 657),
('G1001', '韶关站',    5, '11:15', '11:18', 826),
('G1001', '广州南站',  6, '12:10', NULL, 1069);

-- 广州南→武汉
INSERT IGNORE INTO train_template (train_code, line_code, business_carriage, first_class_carriage, second_class_carriage) VALUES
('G1002', 'WG-JG', 1, 2, 8);
INSERT IGNORE INTO train_template_stopover (train_code, station_name, station_index, in_time, out_time, mileage) VALUES
('G1002', '广州南站',  1, NULL, '13:00', 0),
('G1002', '韶关站',    2, '13:50', '13:53', 243),
('G1002', '郴州西站',  3, '14:25', '14:28', 412),
('G1002', '衡阳东站',  4, '15:03', '15:06', 559),
('G1002', '长沙南站',  5, '15:42', '15:48', 707),
('G1002', '武汉站',    6, '17:10', NULL, 1069);

-- 深圳北→长沙南
INSERT IGNORE INTO train_template (train_code, line_code, business_carriage, first_class_carriage, second_class_carriage) VALUES
('G6001', 'S-C', 1, 2, 6);
INSERT IGNORE INTO train_template_stopover (train_code, station_name, station_index, in_time, out_time, mileage) VALUES
('G6001', '深圳北站',  1, NULL, '10:00', 0),
('G6001', '广州南站',  2, '10:30', '10:37', 102),
('G6001', '清远站',    3, '11:00', '11:03', 182),
('G6001', '郴州西站',  4, '11:55', '11:58', 440),
('G6001', '衡阳东站',  5, '12:33', '12:36', 593),
('G6001', '株洲西站',  6, '13:03', '13:06', 694),
('G6001', '长沙南站',  7, '13:22', NULL, 740);

-- 长沙南→深圳北
INSERT IGNORE INTO train_template (train_code, line_code, business_carriage, first_class_carriage, second_class_carriage) VALUES
('G6002', 'C-S', 1, 2, 6);
INSERT IGNORE INTO train_template_stopover (train_code, station_name, station_index, in_time, out_time, mileage) VALUES
('G6002', '长沙南站',  1, NULL, '15:00', 0),
('G6002', '株洲西站',  2, '15:16', '15:19', 46),
('G6002', '衡阳东站',  3, '15:46', '15:49', 147),
('G6002', '郴州西站',  4, '16:24', '16:27', 300),
('G6002', '清远站',    5, '17:18', '17:21', 558),
('G6002', '广州南站',  6, '17:45', '17:52', 638),
('G6002', '深圳北站',  7, '18:22', NULL, 740);

-- 广州南→南宁东
INSERT IGNORE INTO train_template (train_code, line_code, business_carriage, first_class_carriage, second_class_carriage) VALUES
('G2901', 'G-N', 1, 1, 6);
INSERT IGNORE INTO train_template_stopover (train_code, station_name, station_index, in_time, out_time, mileage) VALUES
('G2901', '广州南站',  1, NULL, '08:30', 0),
('G2901', '佛山西站',  2, '08:50', '08:53', 33),
('G2901', '梧州南站',  3, '10:10', '10:13', 290),
('G2901', '南宁东站',  4, '12:30', NULL, 577);

-- 南宁东→广州南
INSERT IGNORE INTO train_template (train_code, line_code, business_carriage, first_class_carriage, second_class_carriage) VALUES
('G2902', 'N-G', 1, 1, 6);
INSERT IGNORE INTO train_template_stopover (train_code, station_name, station_index, in_time, out_time, mileage) VALUES
('G2902', '南宁东站',  1, NULL, '14:00', 0),
('G2902', '梧州南站',  2, '16:10', '16:13', 287),
('G2902', '佛山西站',  3, '17:30', '17:33', 544),
('G2902', '广州南站',  4, '17:55', NULL, 577);

-- 汉口→宜昌东 (省内D字头)
INSERT IGNORE INTO train_template (train_code, line_code, business_carriage, first_class_carriage, second_class_carriage) VALUES
('D5801', 'W-Y', 0, 1, 6);
INSERT IGNORE INTO train_template_stopover (train_code, station_name, station_index, in_time, out_time, mileage) VALUES
('D5801', '汉口站',    1, NULL, '07:30', 0),
('D5801', '荆州站',    2, '09:00', '09:03', 220),
('D5801', '宜昌东站',  3, '10:00', NULL, 324);

-- 长沙南→怀化南 (省内)
INSERT IGNORE INTO train_template (train_code, line_code, business_carriage, first_class_carriage, second_class_carriage) VALUES
('G6401', 'C-H', 1, 1, 6);
INSERT IGNORE INTO train_template_stopover (train_code, station_name, station_index, in_time, out_time, mileage) VALUES
('G6401', '长沙南站',  1, NULL, '09:00', 0),
('G6401', '娄底南站',  2, '09:45', '09:48', 155),
('G6401', '怀化南站',  3, '10:55', NULL, 332);

-- 武汉→长沙南
INSERT IGNORE INTO train_template (train_code, line_code, business_carriage, first_class_carriage, second_class_carriage) VALUES
('G1101', 'W-C', 1, 2, 8);
INSERT IGNORE INTO train_template_stopover (train_code, station_name, station_index, in_time, out_time, mileage) VALUES
('G1101', '武汉站',    1, NULL, '07:00', 0),
('G1101', '咸宁北站',  2, '07:25', '07:28', 85),
('G1101', '岳阳东站',  3, '08:05', '08:08', 215),
('G1101', '长沙南站',  4, '08:42', NULL, 362);

-- 广州南→珠海 (省内)
INSERT IGNORE INTO train_template (train_code, line_code, business_carriage, first_class_carriage, second_class_carriage) VALUES
('G7101', 'G-Z', 1, 1, 6);
INSERT IGNORE INTO train_template_stopover (train_code, station_name, station_index, in_time, out_time, mileage) VALUES
('G7101', '广州南站',  1, NULL, '18:00', 0),
('G7101', '珠海站',    2, '18:55', NULL, 116);

-- 深圳北→汕头 (省内)
INSERT IGNORE INTO train_template (train_code, line_code, business_carriage, first_class_carriage, second_class_carriage) VALUES
('G6301', 'S-S', 1, 1, 6);
INSERT IGNORE INTO train_template_stopover (train_code, station_name, station_index, in_time, out_time, mileage) VALUES
('G6301', '深圳北站',  1, NULL, '11:00', 0),
('G6301', '惠州南站',  2, '11:25', '11:28', 56),
('G6301', '汕头站',    3, '13:10', NULL, 356);

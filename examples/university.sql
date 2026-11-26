CREATE TABLE students (
  id INT PRIMARY KEY,
  name VARCHAR(40) NOT NULL,
  age INT,
  major VARCHAR(30)
);

CREATE TABLE courses (
  id INT PRIMARY KEY,
  title VARCHAR(60) NOT NULL,
  credits INT NOT NULL
);

CREATE TABLE enrollments (
  student_id INT NOT NULL,
  course_id INT NOT NULL,
  grade DOUBLE
);

CREATE INDEX idx_enrollments_student ON enrollments (student_id);

INSERT INTO students VALUES
  (1, 'Ana Torres', 21, 'Computer Science'),
  (2, 'Bruno Díaz', 19, 'Mathematics'),
  (3, 'Camila Rojas', 23, 'Computer Science'),
  (4, 'Diego Herrera', 20, 'Physics'),
  (5, 'Elena Castro', 22, 'Mathematics'),
  (6, 'Felipe Morales', 24, NULL),
  (7, 'Gabriela Ruiz', 18, 'Computer Science'),
  (8, 'Hugo Vargas', NULL, 'Physics'),
  (9, 'Isabel Mendoza', 21, 'Biology'),
  (10, 'Julián Ortega', 25, 'Computer Science');

INSERT INTO courses VALUES
  (101, 'Databases', 4),
  (102, 'Algorithms', 4),
  (103, 'Linear Algebra', 3),
  (104, 'Operating Systems', 4),
  (105, 'Quantum Mechanics', 5),
  (106, 'Cell Biology', 3);

INSERT INTO enrollments VALUES
  (1, 101, 4.7),
  (1, 102, 4.2),
  (1, 104, 3.9),
  (2, 102, 3.5),
  (2, 103, 4.8),
  (3, 101, 4.1),
  (3, 104, 4.5),
  (4, 103, 3.2),
  (4, 105, 3.8),
  (5, 103, 4.9),
  (5, 102, 4.0),
  (6, 101, 2.8),
  (7, 101, NULL),
  (7, 102, NULL),
  (8, 105, 4.4),
  (9, 106, 4.6),
  (9, 101, 3.7),
  (10, 101, 4.9),
  (10, 104, 4.3),
  (10, 102, 3.1);

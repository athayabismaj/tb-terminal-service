-- Seed data: roles
INSERT INTO system.roles (name, description) VALUES
    ('owner', 'Pemilik toko — akses penuh ke semua fitur'),
    ('admin', 'Admin toko — kelola produk, stok, dan laporan'),
    ('kasir', 'Kasir — proses transaksi penjualan');

-- Seed data: test user for login testing
-- PIN: 123456 (BCrypt hash with cost 12)
INSERT INTO system.users (role_id, name, username, pin_hash) VALUES
    (
        (SELECT id FROM system.roles WHERE name = 'owner'),
        'Pemilik Toko',
        'owner',
        '$2a$12$LJ3m4ys3Lp0Nq.MQP8YKFeBhR7tOxkPVHpGBl4oHGjCr/FTKQWETK'
    );

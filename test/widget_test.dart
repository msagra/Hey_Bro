import 'package:flutter_test/flutter_test.dart';
import 'package:hey_bro/main.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  setUpAll(() {
    TestWidgetsFlutterBinding.ensureInitialized();
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
  });

  testWidgets('Hey Bro app launches without crashing', (WidgetTester tester) async {
    await tester.pumpWidget(const HeyBroApp());
    expect(find.text('Hey Bro'), findsWidgets);
  });
}
